package io.github.loopforge4j.server.tracker;

import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.domain.RepositoryProvider;
import io.github.loopforge4j.core.runtime.git.RepositoryRemote;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.account.UserStore;
import io.github.loopforge4j.server.web.ApiException;
import io.github.loopforge4j.server.work.ExternalRef;
import io.github.loopforge4j.server.work.Requirement;
import io.github.loopforge4j.server.work.RequirementStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Two-way issue sync (upstream {@code tracker.Service}): pulls GitHub and GitLab/工蜂 issues and
 * TAPD stories and bugs into project requirements, imports single TAPD records by link, pushes local
 * requirements out, and comments on the external item when a run delivers pull requests. After
 * intake the local lifecycle (status, assignee, SOP, runs) belongs to the platform; pulls refresh
 * only the tracker's content.
 */
@Service
public class TrackerService {

    private static final Logger log = LoggerFactory.getLogger(TrackerService.class);
    private static final int LOG_LIMIT = 100;

    /** One sync attempt, shown in the console. */
    public record SyncLog(
            Instant at,
            String projectId,
            String source,
            String status,
            int created,
            int updated,
            int removed,
            String message,
            String actor) {}

    private final TrackerStore settings;
    private final RequirementStore requirements;
    private final CatalogStore catalog;
    private final UserStore users;
    private final GitHubIssues github;
    private final GitLabIssues gitlab;
    private final TapdClient tapd;
    private final Clock clock;
    private final Deque<SyncLog> logs = new ArrayDeque<>();
    private final Map<String, ReentrantLock> projectLocks = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> writeBackLocks = new ConcurrentHashMap<>();

    public TrackerService(
            TrackerStore settings,
            RequirementStore requirements,
            CatalogStore catalog,
            UserStore users,
            GitHubIssues github,
            GitLabIssues gitlab,
            TapdClient tapd,
            Clock clock) {
        this.settings = settings;
        this.requirements = requirements;
        this.catalog = catalog;
        this.users = users;
        this.github = github;
        this.gitlab = gitlab;
        this.tapd = tapd;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ settings

    public TrackerSettings settings(String projectId) {
        return settings.settings(projectId);
    }

    public TrackerSettings saveSettings(String projectId, TrackerSettings input, String actor) {
        List<String> problems = new ArrayList<>();
        List<String> repositories = TrackerStore.ids(input.issueRepositoryIds());
        if (repositories.size() > 20) {
            problems.add("at most 20 issue repositories");
        }
        for (String id : repositories) {
            try {
                issueSource(projectId, id);
            } catch (ApiException e) {
                problems.add(e.getMessage());
            }
        }
        if ((input.tapdWorkspaceId() == null) != (input.tapdIterationId() == null)) {
            problems.add("tapdWorkspaceId and tapdIterationId go together");
        }
        for (String value : new String[] {input.tapdWorkspaceId(), input.tapdIterationId()}) {
            if (value != null && !value.matches("\\d{1,20}")) {
                problems.add("TAPD ids are numbers");
            }
        }
        if (input.tapdPool() && !tapd.configured()) {
            problems.add("no TAPD API account is configured (loopforge.tapd.api-user)");
        }
        if (!problems.isEmpty()) {
            throw new io.github.loopforge4j.core.engine.WorkflowValidationException(problems);
        }
        return settings.save(
                projectId,
                new TrackerSettings(
                        repositories,
                        input.tapdWorkspaceId(),
                        input.tapdIterationId(),
                        input.autoSync(),
                        input.writeBack(),
                        actor,
                        clock.instant()));
    }

    public List<SyncLog> logs(String projectId) {
        synchronized (logs) {
            return logs.stream()
                    .filter(l -> projectId == null || projectId.equals(l.projectId()))
                    .toList();
        }
    }

    // ------------------------------------------------------------------ pull

    /** Pulls every tracker of the project; one failing tracker does not stop the others. */
    public List<SyncLog> sync(String projectId, String actor) {
        ReentrantLock lock = projectLocks.computeIfAbsent(projectId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ApiException(HttpStatus.CONFLICT, "a sync of this project is already running");
        }
        try {
            TrackerSettings config = settings.settings(projectId);
            List<SyncLog> out = new ArrayList<>();
            for (String repositoryId : config.issueRepositoryIds()) {
                out.add(pullRepository(projectId, repositoryId, actor));
            }
            if (config.tapdPool()) {
                out.add(pullTapdPool(projectId, config, actor));
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    private SyncLog pullRepository(String projectId, String repositoryId, String actor) {
        int[] counts = new int[3];
        String source = "repository:" + repositoryId;
        try {
            IssueSource src = issueSource(projectId, repositoryId);
            List<ExternalItem> items = src.list();
            for (ExternalItem item : items) {
                ExternalRef ref = new ExternalRef(
                        src.kind(),
                        "issue",
                        item.externalId(),
                        item.number(),
                        item.url(),
                        item.status(),
                        repositoryId,
                        null,
                        false,
                        clock.instant(),
                        List.of());
                upsert(projectId, ref, item, List.of(repositoryId), item.open() ? "todo" : "closed", counts);
            }
            return record(projectId, source, "ok", counts, "pulled " + items.size() + " issues", actor);
        } catch (TrackerException | ApiException e) {
            return record(projectId, source, "failed", counts, e.getMessage(), actor);
        }
    }

    private SyncLog pullTapdPool(String projectId, TrackerSettings config, String actor) {
        int[] counts = new int[3];
        String source = "tapd:" + config.tapdWorkspaceId() + "/" + config.tapdIterationId();
        try {
            Set<String> remote = new HashSet<>();
            int total = 0;
            for (String kind : List.of("story", "bug")) {
                List<ExternalItem> items = tapd.iteration(config.tapdWorkspaceId(), config.tapdIterationId(), kind);
                total += items.size();
                for (ExternalItem item : items) {
                    ExternalRef ref = tapdRef(config.tapdWorkspaceId(), item, false);
                    remote.add(ref.key());
                    upsert(projectId, ref, item, List.of(), TapdClient.status(kind, item.status()), counts);
                }
            }
            counts[2] = reconcileTapd(projectId, config.tapdWorkspaceId(), remote);
            return record(projectId, source, "ok", counts, "pulled " + total + " TAPD records", actor);
        } catch (TrackerException e) {
            return record(projectId, source, "failed", counts, e.getMessage(), actor);
        }
    }

    /**
     * New open items become requirements; known ones get fresh content and tracker state. Items that
     * are already closed when first seen are not imported (history would flood the backlog).
     */
    private Optional<Requirement> upsert(
            String projectId,
            ExternalRef ref,
            ExternalItem item,
            List<String> repositories,
            String status,
            int[] counts) {
        Optional<Requirement> existing = requirements.findByExternal(projectId, ref.key());
        if (existing.isPresent()) {
            Requirement current = existing.get();
            ExternalRef merged = current.external() == null
                    ? ref
                    : current.external().refreshed(ref.url(), ref.state(), clock.instant());
            if (ref.imported()) {
                merged = merged.markImported();
            }
            ExternalRef finalRef = merged;
            Optional<Requirement> updated = requirements.update(
                    current.id(), r -> r.synced(clip(item.title(), 500), clip(item.description(), 100_000), finalRef));
            counts[1]++;
            return updated;
        }
        if (!item.open() && !ref.imported()) {
            return Optional.empty();
        }
        try {
            Requirement created = requirements.create(new Requirement(
                    null,
                    projectId,
                    0,
                    clip(item.title().isBlank() ? "（无标题）" : item.title(), 500),
                    clip(item.description(), 100_000),
                    status,
                    item.priority(),
                    assignee(item.owner()),
                    null,
                    repositories,
                    null,
                    null,
                    null,
                    null,
                    ref.source(),
                    ref,
                    "sync",
                    null,
                    null));
            counts[0]++;
            return Optional.of(created);
        } catch (DuplicateKeyException e) {
            // created concurrently (another instance or a manual import): refresh it instead
            return upsert(projectId, ref, item, repositories, status, counts);
        }
    }

    /**
     * Pool items that left the iteration disappear locally while nobody has started on them;
     * imported items and anything with runs stay.
     */
    private int reconcileTapd(String projectId, String workspace, Set<String> remote) {
        int removed = 0;
        for (Requirement r : requirements.list(projectId, null)) {
            ExternalRef ref = r.external();
            if (ref == null
                    || !"tapd".equals(ref.source())
                    || ref.imported()
                    || !workspace.equals(ref.workspaceId())
                    || remote.contains(ref.key())
                    || !r.runIds().isEmpty()
                    || !Set.of("backlog", "todo").contains(r.status())) {
                continue;
            }
            if (requirements.delete(r.id())) {
                removed++;
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ import and push

    /** Imports one TAPD story, bug or task from a pasted link (upstream {@code ImportTAPDStory}). */
    public Requirement importTapd(String projectId, String text, String actor) {
        TapdLinks.Ref link;
        try {
            link = TapdLinks.parse(text, Set.of(tapd.webHost()));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
        try {
            ExternalItem item = tapd.get(link.workspaceId(), link.kind(), link.id());
            ExternalRef ref = tapdRef(link.workspaceId(), item, true);
            int[] counts = new int[3];
            Requirement result = upsert(
                            projectId, ref, item, List.of(), TapdClient.status(link.kind(), item.status()), counts)
                    .orElseThrow();
            record(
                    projectId,
                    "tapd:" + link.workspaceId(),
                    "ok",
                    counts,
                    "imported " + link.kind() + " " + link.id(),
                    actor);
            return result;
        } catch (TrackerException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /**
     * Creates the external item for a local requirement and links them: an issue in a catalog
     * repository, or a story in the project's TAPD iteration.
     *
     * @param target a repository id, or {@code tapd}
     */
    public Requirement push(String projectId, int number, String target, String actor) {
        Requirement requirement = requirements
                .find(projectId, number)
                .orElseThrow(() -> new io.github.loopforge4j.core.engine.RunNotFoundException(
                        "requirement #" + number + " not found"));
        if (requirement.external() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "requirement #" + number + " is already linked to "
                            + requirement.external().url());
        }
        String body =
                (requirement.description() == null ? "" : requirement.description()) + "\n\n— LoopForge 需求 #" + number;
        ExternalRef ref;
        try {
            if ("tapd".equals(target)) {
                TrackerSettings config = settings.settings(projectId);
                if (!config.tapdPool()) {
                    throw new ApiException(
                            HttpStatus.UNPROCESSABLE_CONTENT, "the project has no TAPD iteration configured");
                }
                ExternalItem story =
                        tapd.createStory(config.tapdWorkspaceId(), config.tapdIterationId(), requirement.title(), body);
                ref = tapdRef(config.tapdWorkspaceId(), story, true);
            } else {
                IssueSource src = issueSource(projectId, target);
                ExternalItem issue = src.create(requirement.title(), body);
                ref = new ExternalRef(
                        src.kind(),
                        "issue",
                        issue.externalId(),
                        issue.number(),
                        issue.url(),
                        issue.status(),
                        target,
                        null,
                        true,
                        clock.instant(),
                        List.of());
            }
        } catch (TrackerException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        log.info("requirement {} #{} pushed to {} by {}", projectId, number, ref.url(), actor);
        return requirements.update(requirement.id(), r -> r.withExternal(ref)).orElseThrow();
    }

    // ------------------------------------------------------------------ write-back

    /**
     * Comments the requirement's delivered pull requests on its external item, each once. Runs off
     * the caller's thread; failures are logged, the next delivery change retries.
     */
    public void deliveriesChanged(Requirement requirement) {
        if (requirement.external() == null || requirement.deliveries().isEmpty()) {
            return;
        }
        Set<String> notified = new HashSet<>(requirement.external().notified());
        if (requirement.deliveries().stream().allMatch(p -> p.url() == null || notified.contains(p.url()))) {
            return;
        }
        Thread.ofVirtual()
                .name("tracker-writeback-" + requirement.id())
                .start(() -> writeBack(requirement.id(), requirement.projectId(), requirement.number()));
    }

    void writeBack(String requirementId, String projectId, int number) {
        ReentrantLock lock = writeBackLocks.computeIfAbsent(requirementId, k -> new ReentrantLock());
        lock.lock();
        try {
            Requirement current = requirements.find(projectId, number).orElse(null);
            if (current == null
                    || current.external() == null
                    || !settings.settings(projectId).writeBack()) {
                return;
            }
            ExternalRef ref = current.external();
            LinkedHashSet<String> done = new LinkedHashSet<>(ref.notified());
            List<PullRequest> fresh = current.deliveries().stream()
                    .filter(p -> p.url() != null && !done.contains(p.url()))
                    .toList();
            if (fresh.isEmpty()) {
                return;
            }
            StringBuilder text = new StringBuilder("LoopForge 已交付：\n");
            for (PullRequest pr : fresh) {
                text.append("- ")
                        .append(pr.url())
                        .append("（")
                        .append(pr.state())
                        .append("）\n");
            }
            comment(projectId, ref, text.toString().strip());
            fresh.forEach(p -> done.add(p.url()));
            List<String> urls = List.copyOf(done);
            requirements.update(current.id(), r -> r.withExternal(r.external().withNotified(urls)));
        } catch (TrackerException | RuntimeException e) {
            log.warn(
                    "cannot write deliveries of requirement {} back to its tracker: {}", requirementId, e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private void comment(String projectId, ExternalRef ref, String text) throws TrackerException {
        if ("tapd".equals(ref.source())) {
            tapd.comment(ref.workspaceId(), ref.kind(), ref.externalId(), text);
        } else {
            issueSource(projectId, ref.repositoryId()).comment(ref.number(), text);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The issue tracker behind a catalog repository the project may use. */
    interface IssueSource {
        String kind();

        List<ExternalItem> list() throws TrackerException;

        ExternalItem create(String title, String body) throws TrackerException;

        void comment(int number, String body) throws TrackerException;
    }

    IssueSource issueSource(String projectId, String repositoryId) {
        RepositoryDefinition repo = catalog.repository(repositoryId)
                .filter(r -> r.projectId() == null
                        || r.projectId().isBlank()
                        || r.projectId().equals(projectId))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "repository " + repositoryId + " is not available to the project"));
        if (repo.provider() == RepositoryProvider.GITHUB && repo.githubRepo() != null) {
            String name = repo.githubRepo();
            return new IssueSource() {
                public String kind() {
                    return "github";
                }

                public List<ExternalItem> list() throws TrackerException {
                    return github.list(name);
                }

                public ExternalItem create(String title, String body) throws TrackerException {
                    return github.create(name, title, body);
                }

                public void comment(int number, String body) throws TrackerException {
                    github.comment(name, number, body);
                }
            };
        }
        Optional<RepositoryRemote> remote = RepositoryRemote.parse(repo.cloneUrl());
        if (repo.provider() == RepositoryProvider.GITLAB
                && remote.isPresent()
                && gitlab.supports(remote.get().host())) {
            String host = remote.get().host();
            String path = remote.get().path();
            return new IssueSource() {
                public String kind() {
                    return "gitlab";
                }

                public List<ExternalItem> list() throws TrackerException {
                    return gitlab.list(host, path);
                }

                public ExternalItem create(String title, String body) throws TrackerException {
                    return gitlab.create(host, path, title, body);
                }

                public void comment(int number, String body) throws TrackerException {
                    gitlab.comment(host, path, number, body);
                }
            };
        }
        throw new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "repository " + repositoryId
                        + " has no issue tracker (GitHub, or GitLab/工蜂 on a host in loopforge.gitlab)");
    }

    private ExternalRef tapdRef(String workspace, ExternalItem item, boolean imported) {
        return new ExternalRef(
                "tapd",
                item.kind(),
                item.externalId(),
                0,
                item.url(),
                item.status(),
                null,
                workspace,
                imported,
                clock.instant(),
                List.of());
    }

    /** The tracker's handler becomes the assignee when an account has that username. */
    private String assignee(String owner) {
        if (owner == null || owner.isBlank()) {
            return null;
        }
        String name = owner.strip().toLowerCase(Locale.ROOT);
        return users.byUsername(name).map(u -> u.username()).orElse(null);
    }

    private SyncLog record(String projectId, String source, String status, int[] counts, String message, String actor) {
        SyncLog entry = new SyncLog(
                clock.instant(),
                projectId,
                source,
                status,
                counts[0],
                counts[1],
                counts[2],
                message == null ? "" : clip(message, 300),
                actor);
        synchronized (logs) {
            logs.addFirst(entry);
            while (logs.size() > LOG_LIMIT) {
                logs.removeLast();
            }
        }
        if ("failed".equals(status)) {
            log.warn("tracker sync {} of project {} failed: {}", source, projectId, message);
        }
        return entry;
    }

    private static String clip(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
