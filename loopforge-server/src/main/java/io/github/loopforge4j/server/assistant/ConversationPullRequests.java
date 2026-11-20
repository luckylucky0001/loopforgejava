package io.github.loopforge4j.server.assistant;

import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.git.GitCredentials;
import io.github.loopforge4j.server.account.PersonalCredentials;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import io.github.loopforge4j.server.work.Requirement;
import io.github.loopforge4j.server.work.RequirementService;
import io.github.loopforge4j.server.work.RequirementStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Pull requests opened from a project conversation (upstream: registered before each reply is
 * saved). The person speaking opened them with their own GitHub token; one counts when its author is
 * that token's user, it was created after the conversation started, its head is the tip of a local
 * branch in the conversation's workspace, it is not closed without merging, and no requirement
 * tracks it yet. It is registered on the conversation's requirement, or on a new one made from the
 * pull request, which then waits for review; a poller marks the requirement done once its pull
 * requests are merged.
 */
@Service
public class ConversationPullRequests {

    private static final Logger log = LoggerFactory.getLogger(ConversationPullRequests.class);
    private static final Set<String> FINISHED = Set.of("done", "closed", "cancelled");

    private final PersonalCredentials personal;
    private final RequirementStore requirements;
    private final RequirementService requirementService;
    private final AssistantStore sessions;
    private final URI api;
    private final String webHost;
    private final LoopForgeProperties properties;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ConversationPullRequests(
            PersonalCredentials personal,
            RequirementStore requirements,
            RequirementService requirementService,
            AssistantStore sessions,
            LoopForgeProperties properties) {
        this.personal = personal;
        this.requirements = requirements;
        this.requirementService = requirementService;
        this.sessions = sessions;
        this.properties = properties;
        this.api = properties.github().apiUrl();
        String host = api.getHost() == null ? "" : api.getHost().toLowerCase(java.util.Locale.ROOT);
        this.webHost = "api.github.com".equals(host) ? "github.com" : host;
    }

    /** A pull request found on GitHub. */
    record Found(
            String repo,
            int number,
            String url,
            String title,
            String body,
            String state,
            Instant mergedAt,
            String head,
            String base,
            String sha) {}

    /**
     * Registers the speaker's new pull requests of this conversation.
     *
     * @return a line for the reply, when something was registered
     */
    public Optional<String> register(AssistantSession session, String speaker, Path workspace) {
        if (session.projectId() == null || workspace == null) {
            return Optional.empty();
        }
        GitCredentials.HttpsCredential credential = personal.gitHttps(speaker).get(webHost);
        if (credential == null) {
            return Optional.empty();
        }
        List<WorkspaceCheckouts.Checkout> checkouts = WorkspaceCheckouts.find(workspace);
        if (checkouts.isEmpty()) {
            return Optional.empty();
        }
        try {
            String login = get("/user", credential.token()).path("login").asString("");
            if (login.isBlank()) {
                return Optional.empty();
            }
            Set<String> tracked = trackedUrls(session.projectId());
            List<Found> found = new ArrayList<>();
            Set<String> seen = new java.util.HashSet<>();
            for (WorkspaceCheckouts.Checkout checkout : checkouts) {
                String[] repo = WorkspaceCheckouts.repository(checkout.origin());
                if (repo == null || !repo[0].equals(webHost) || !seen.add(repo[1])) {
                    continue;
                }
                JsonNode pulls = get(
                        "/repos/" + repo[1] + "/pulls?state=all&sort=created&direction=desc&per_page=30",
                        credential.token());
                for (JsonNode pr : pulls) {
                    Found candidate = found(repo[1], pr);
                    Instant created = Instant.parse(pr.path("created_at").asString("1970-01-01T00:00:00Z"));
                    boolean mine =
                            login.equalsIgnoreCase(pr.path("user").path("login").asString(""));
                    boolean usable = "open".equals(candidate.state()) || candidate.mergedAt() != null;
                    if (mine
                            && usable
                            && created.isAfter(session.createdAt())
                            && checkout.tips().contains(candidate.sha())
                            && !tracked.contains(candidate.url())) {
                        found.add(candidate);
                    }
                }
            }
            if (found.isEmpty()) {
                return Optional.empty();
            }
            Requirement target = target(session, speaker, found.getFirst());
            Requirement updated = requirements
                    .update(target.id(), current -> {
                        Map<String, PullRequest> byUrl = new LinkedHashMap<>();
                        current.deliveries().forEach(p -> byUrl.put(p.url(), p));
                        found.forEach(f -> byUrl.putIfAbsent(f.url(), pullRequest(f)));
                        List<PullRequest> deliveries = List.copyOf(byUrl.values());
                        return current.with(status(deliveries), current.runIds(), current.latestRunId(), deliveries);
                    })
                    .orElse(target);
            sessions.linkRequirement(session.id(), updated.id());
            StringBuilder note = new StringBuilder("已登记 PR：");
            for (int i = 0; i < found.size(); i++) {
                note.append(i == 0 ? "" : "、")
                        .append(found.get(i).repo())
                        .append('#')
                        .append(found.get(i).number());
            }
            note.append(" → 需求 #").append(updated.number());
            return Optional.of(note.toString());
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("conversation {}: pull request registration failed: {}", session.id(), e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The conversation's unfinished requirement, or a new one made from the first pull request. */
    private Requirement target(AssistantSession session, String speaker, Found first) {
        if (session.requirementId() != null) {
            Optional<Requirement> linked = requirements.list(session.projectId(), null).stream()
                    .filter(r -> r.id().equals(session.requirementId()) && !FINISHED.contains(r.status()))
                    .findFirst();
            if (linked.isPresent()) {
                return linked.get();
            }
        }
        String description = (first.body() == null ? "" : first.body().strip());
        if (description.length() > 5000) {
            description = description.substring(0, 5000) + "…";
        }
        description = (description.isEmpty() ? "" : description + "\n\n") + "来自对话「" + session.title() + "」";
        String title =
                first.title() == null || first.title().isBlank() ? first.repo() + "#" + first.number() : first.title();
        return requirementService.create(
                session.projectId(),
                new Requirement(
                        null,
                        session.projectId(),
                        0,
                        title.length() > 200 ? title.substring(0, 200) : title,
                        description,
                        "in_progress",
                        "medium",
                        speaker,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        speaker,
                        null,
                        null,
                        null,
                        List.of()),
                speaker);
    }

    private Set<String> trackedUrls(String projectId) {
        Set<String> urls = new java.util.HashSet<>();
        requirements.list(projectId, null).forEach(r -> r.deliveries().forEach(p -> urls.add(p.url())));
        return urls;
    }

    /** Waiting for review while anything is open; done once everything is merged. */
    static String status(List<PullRequest> deliveries) {
        boolean open = deliveries.stream().anyMatch(PullRequest::open);
        boolean merged = deliveries.stream().anyMatch(p -> "merged".equals(p.state()));
        return !open && merged ? "done" : "in_review";
    }

    private static Found found(String repo, JsonNode pr) {
        String merged = pr.path("merged_at").asString(null);
        String state = merged != null && !merged.isBlank()
                ? "merged"
                : pr.path("state").asString("open");
        return new Found(
                repo,
                pr.path("number").asInt(),
                pr.path("html_url").asString(""),
                pr.path("title").asString(""),
                pr.path("body").asString(""),
                state,
                merged == null || merged.isBlank() ? null : Instant.parse(merged),
                pr.path("head").path("ref").asString(""),
                pr.path("base").path("ref").asString(""),
                pr.path("head").path("sha").asString(""));
    }

    private static PullRequest pullRequest(Found f) {
        return new PullRequest(
                null, f.number(), f.url(), f.state(), f.head(), f.base(), f.sha(), f.mergedAt(), Instant.now());
    }

    private JsonNode get(String path, String token) throws java.io.IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api.toString().replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "loopforge4j")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new java.io.IOException(
                    "GitHub API " + path.replaceAll("\\?.*", "") + ": HTTP " + response.statusCode());
        }
        return Json.mapper().readTree(response.body());
    }

    /**
     * Merge tracking of registered pull requests (requirements without SOP runs; runs track their
     * own): asks GitHub with the requirement creator's token, else the deployment's.
     */
    @Scheduled(
            initialDelayString = "${loopforge.github.refresh-interval:5m}",
            fixedDelayString = "${loopforge.github.refresh-interval:5m}")
    public void refresh() {
        for (Requirement r : requirements.byStatus("in_review", 500)) {
            if (!r.runIds().isEmpty() || r.deliveries().stream().noneMatch(PullRequest::open)) {
                continue;
            }
            String token = Optional.ofNullable(personal.gitHttps(r.createdBy()).get(webHost))
                    .map(GitCredentials.HttpsCredential::token)
                    .orElseGet(this::deploymentToken);
            if (token == null) {
                continue;
            }
            List<PullRequest> deliveries = new ArrayList<>();
            boolean changed = false;
            for (PullRequest p : r.deliveries()) {
                String repo = repoOf(p.url());
                if (!p.open() || repo == null) {
                    deliveries.add(p);
                    continue;
                }
                try {
                    Found now = found(repo, get("/repos/" + repo + "/pulls/" + p.number(), token));
                    if (!now.state().equals(p.state())) {
                        changed = true;
                    }
                    deliveries.add(new PullRequest(
                            p.repositoryId(),
                            p.number(),
                            p.url(),
                            now.state(),
                            p.headBranch(),
                            p.baseBranch(),
                            now.sha().isBlank() ? p.headSha() : now.sha(),
                            now.mergedAt(),
                            Instant.now()));
                } catch (RuntimeException | java.io.IOException e) {
                    log.debug("refreshing {} failed: {}", p.url(), e.toString());
                    deliveries.add(p);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (changed) {
                requirements.update(
                        r.id(),
                        current ->
                                current.with(status(deliveries), current.runIds(), current.latestRunId(), deliveries));
            }
        }
    }

    private String deploymentToken() {
        return properties.git().https().stream()
                .filter(c -> c.host().equalsIgnoreCase(webHost))
                .map(LoopForgeProperties.HttpsCredential::token)
                .findFirst()
                .orElse(null);
    }

    /** {@code owner/repo} of a pull request page on the configured GitHub. */
    private String repoOf(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || !uri.getHost().equalsIgnoreCase(webHost)) {
                return null;
            }
            String[] parts = uri.getPath().split("/");
            return parts.length >= 5 && "pull".equals(parts[3]) ? parts[1] + "/" + parts[2] : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
