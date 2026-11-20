package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.engine.Conversations;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.Telemetry;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.account.ProjectRole;
import io.github.loopforge4j.server.assistant.AssistantStreams;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import io.github.loopforge4j.server.tracker.TrackerException;
import io.github.loopforge4j.server.tracker.TrackerService;
import io.github.loopforge4j.server.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI clarification of requirements (upstream issue chat-clarify). A round runs in the background on
 * the server: the analyst agent works in a per-requirement workspace with read-only checkouts of the
 * requirement's repositories, continues its native CLI session across rounds, and ends with the
 * {@link ClarificationProtocol} result. Questions wait for the person's answers; a conclusion is
 * written into the requirement's description and back to its tracker.
 */
@Service
public class ClarificationService {

    private static final Logger log = LoggerFactory.getLogger(ClarificationService.class);
    private static final int MAX_MESSAGE = 20_000;
    private static final int ACTIVITY_LINES = 60;

    static final String SYSTEM = """
            你是研发工作项分析师，正在澄清一条已有的需求。这是多轮澄清：结合「对话」里已经问过和回答过的内容继续，不要重复已问过的问题。
            1. 读取当前工作目录里检出的代码（只读），定位这条需求要改的位置和现有实现
            2. 「## 需求」里有外部原单链接、文档链接时，能读取就先读取
            3. 围绕这条需求具体要改什么、改到什么程度、怎样验收，基于代码和对话历史判断还缺什么阻塞开工的信息
            4. 按下面的输出格式给出本轮结果，只有给出结果才算结束本轮

            范围规则（最重要）：
            - 「## 需求」里的标题和描述就是本次范围的边界。只澄清把这一条需求做完所必需的信息，不负责规划整个模块或项目
            - 读代码是为了定位改动点、确认现有行为，不是为了寻找还能顺带做的事。代码里看到的相邻功能、历史问题、可优化点、未来扩展，都不要提问
            - 不要提出会扩大范围的问题或选项（例如「是否同时支持 X」「要不要顺便改 Y」）。确实值得提醒的相邻事项，只在 summary 的「不在本次范围」里列一句
            - 用户回答里带出这条需求以外的新诉求时，不要沿着追问，记入「不在本次范围」并建议另建需求
            - 不要为了凑问题而提问：能按现有代码惯例合理假设的细节直接假设，写进 summary；没有阻塞开工的缺口就设 ready=true

            问题要具体、基于代码：
            - 不问"范围是什么"，而是问"需求说改 X，代码里 X 由 A 处理，是在 A 上改还是新增入口？"——问题必须落在需求原文已经提到的东西上
            - 每轮只问 1-3 个最关键的新问题，放入 questions，不要只在 summary 中列出缺口
            - 不要修改、提交或推送代码；这里只做分析
            """;

    private final RequirementStore requirements;
    private final ClarificationStore store;
    private final AccessPolicy access;
    private final ObjectProvider<RunEngine> engines;
    private final CatalogStore catalog;
    private final ObjectProvider<TrackerService> trackers;
    private final AssistantStreams streams;
    private final LoopForgeProperties.Assistant config;
    private final Clock clock;
    private final ScheduledExecutorService watchers =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());

    public ClarificationService(
            RequirementStore requirements,
            ClarificationStore store,
            AccessPolicy access,
            ObjectProvider<RunEngine> engines,
            CatalogStore catalog,
            ObjectProvider<TrackerService> trackers,
            AssistantStreams streams,
            LoopForgeProperties properties,
            Clock clock) {
        this.requirements = requirements;
        this.store = store;
        this.access = access;
        this.engines = engines;
        this.catalog = catalog;
        this.trackers = trackers;
        this.streams = streams;
        this.config = properties.assistant();
        this.clock = clock;
    }

    static String topic(String requirementId) {
        return "clarify:" + requirementId;
    }

    static String conversationId(String requirementId) {
        return "clarify-" + requirementId;
    }

    /** The clarification of a requirement; a fresh one when none was started. */
    public Clarification get(String projectId, int number, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        Requirement requirement = require(projectId, number);
        return store.find(requirement.id()).orElseGet(() -> fresh(requirement, null));
    }

    public SseEmitter stream(String projectId, int number, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return streams.subscribe(topic(require(projectId, number).id()));
    }

    /**
     * Starts a round with the person's answer ({@code null} or blank for the first round, or to let
     * the analyst continue); the result arrives on the stream.
     */
    public Clarification start(String projectId, int number, String message, Principal principal) {
        requireEnabled();
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        CurrentUser user = human(principal);
        Requirement requirement = require(projectId, number);
        String answer = message == null ? null : message.strip();
        if (answer != null && answer.length() > MAX_MESSAGE) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "answers are limited to " + MAX_MESSAGE + " characters");
        }
        Clarification current = store.find(requirement.id()).orElseGet(() -> fresh(requirement, user.username()));
        if (current.running()) {
            throw new ApiException(HttpStatus.CONFLICT, "the requirement is being clarified");
        }
        String before = current.status();
        access.requireIsolatedProfile(principal, current.sandboxProfileId());
        if (!engines.getObject().conversations().available(current.sandboxProfileId())) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "sandbox profile '" + current.sandboxProfileId() + "' is not available");
        }
        Clarification.Builder next = current.toBuilder();
        next.pending = answer == null || answer.isEmpty() ? null : answer;
        next.startedBy = user.username();
        next.error = null;
        next.activity = List.of();
        Clarification claimed = next.build();
        Instant stale = clock.instant().minus(config.clarifyTimeout()).minus(Duration.ofMinutes(2));
        if (!store.claim(claimed, stale)) {
            throw new ApiException(HttpStatus.CONFLICT, "the requirement is being clarified");
        }
        Clarification running = store.find(requirement.id()).orElse(claimed);
        streams.publish(topic(requirement.id()), "status", Map.of("status", "analyzing"));
        Thread.ofVirtual()
                .name("clarify-" + requirement.id())
                .start(() -> runRound(
                        requirement,
                        running,
                        user.username(),
                        Clarification.IDLE.contains(before) ? before : "in_progress"));
        return running;
    }

    public void cancel(String projectId, int number, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        if (!store.requestCancel(require(projectId, number).id())) {
            throw new ApiException(HttpStatus.CONFLICT, "no clarification round is running");
        }
    }

    /** An edited conclusion or draft from the person (upstream clarification submission). */
    public record Submission(String summary, boolean ready) {}

    public Clarification submit(String projectId, int number, Submission body, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        CurrentUser user = human(principal);
        Requirement requirement = require(projectId, number);
        String summary = body.summary() == null ? null : body.summary().strip();
        if (summary != null && summary.length() > MAX_MESSAGE) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "the conclusion is limited to " + MAX_MESSAGE + " characters");
        }
        if (body.ready() && (summary == null || summary.isEmpty())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "a conclusion needs a summary");
        }
        Clarification current = store.find(requirement.id()).orElseGet(() -> fresh(requirement, user.username()));
        if (current.running()) {
            throw new ApiException(HttpStatus.CONFLICT, "the requirement is being clarified");
        }
        Clarification.Builder next = current.toBuilder();
        next.summary = summary;
        if (body.ready()) {
            next.status = "ready";
            next.questions = List.of();
        } else {
            next.status = current.history().isEmpty() && summary == null ? "new" : "in_progress";
        }
        Clarification edited = next.build();
        // held like a short round, so no AI round starts while the conclusion is written back
        if (!store.claim(edited, Instant.EPOCH)) {
            throw new ApiException(HttpStatus.CONFLICT, "the requirement is being clarified");
        }
        Clarification result;
        try {
            result = body.ready() ? concluded(requirement.id(), edited) : edited;
        } catch (RuntimeException e) {
            store.finish(current);
            throw e;
        }
        Clarification saved = store.finish(result);
        streams.publish(topic(requirement.id()), "done", saved);
        return saved;
    }

    /** Starts over: forgets the dialogue and removes the analysis workspace. */
    public void reset(String projectId, int number, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        Requirement requirement = require(projectId, number);
        Optional<Clarification> current = store.find(requirement.id());
        if (current.isEmpty()) {
            return;
        }
        if (!store.deleteIdle(requirement.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "stop the running round first");
        }
        release(requirement.id(), current.get().sandboxProfileId());
    }

    /** Removes the analysis workspace of a deleted requirement. */
    void requirementDeleted(String requirementId) {
        release(requirementId, config.sandboxProfile());
    }

    private void release(String requirementId, String profile) {
        Thread.ofVirtual().start(() -> {
            try {
                engines.getObject().conversations().release(conversationId(requirementId), profile);
            } catch (Exception e) {
                log.warn("cannot remove the clarification workspace of {}: {}", requirementId, e.toString());
            }
        });
    }

    // ------------------------------------------------------------------ the round

    /**
     * @param previous the status to return to when the round produces no result
     */
    private void runRound(Requirement requirement, Clarification running, String speaker, String previous) {
        String id = requirement.id();
        String topic = topic(id);
        Cancellation cancellation = Cancellation.create();
        List<String> activity = new ArrayList<>();
        ScheduledFuture<?> watcher = watchers.scheduleWithFixedDelay(
                () -> store.find(id)
                        .filter(c -> "cancelling".equals(c.status()))
                        .ifPresent(c -> cancellation.cancel()),
                1,
                1,
                TimeUnit.SECONDS);
        Clarification.Builder outcome = running.toBuilder();
        try {
            Conversations conversations = engines.getObject().conversations();
            note(topic, activity, "status · 正在检出代码仓库…");
            List<Conversations.Checkout> checkouts = conversations.checkout(
                    conversationId(id),
                    running.sandboxProfileId(),
                    repositories(requirement),
                    requirement.baseBranch(),
                    cancellation);
            for (Conversations.Checkout c : checkouts) {
                note(
                        topic,
                        activity,
                        c.ok()
                                ? "repository · " + c.name() + " @ " + c.baseBranch() + " → " + c.dir()
                                : "repository · " + c.name() + " 检出失败：" + ClarificationProtocol.clip(c.error(), 200));
            }
            AgentDefinition analyst = AgentDefinition.builder("requirement-analyst")
                    .name("需求分析师")
                    .executor(running.executorId())
                    .build();
            String prompt = running.cliSessionId() != null
                    ? resumePrompt(requirement, running)
                    : firstPrompt(requirement, running, checkouts);
            ClarificationProtocol.Result result = null;
            String problem = null;
            for (int attempt = 0; attempt < 2 && result == null; attempt++) {
                Conversations.Reply reply = conversations.turn(
                        new Conversations.Turn(
                                conversationId(id),
                                running.sandboxProfileId(),
                                analyst,
                                prompt,
                                outcome.cliSessionId,
                                config.clarifyTimeout(),
                                List.of(),
                                List.of(),
                                Map.of("LOOPFORGE_REQUIREMENT_ID", id)),
                        telemetry -> {
                            String line = line(telemetry);
                            if (line != null) {
                                note(topic, activity, line);
                            }
                        },
                        cancellation);
                if (reply.sessionId() != null) {
                    outcome.cliSessionId = reply.sessionId();
                }
                outcome.totalTokens += reply.usage().totalTokens();
                outcome.costUsd += reply.usage().costUsd();
                if (reply.failed()) {
                    problem = "分析失败：" + ClarificationProtocol.clip(String.valueOf(reply.text()), 300);
                    break;
                }
                try {
                    result = ClarificationProtocol.parse(reply.text());
                } catch (ClarificationProtocol.InvalidResult e) {
                    problem = "分析结果无效：" + e.getMessage();
                    if (outcome.cliSessionId == null) {
                        break; // nothing to continue: a fresh retry would redo the whole analysis
                    }
                    prompt = ClarificationProtocol.RETRY + e.getMessage();
                }
            }
            outcome.activity = snapshot(activity);
            if (result == null) {
                // the answer the round was about stays pending, so the person can send it again
                outcome.status = previous;
                outcome.error = problem;
                publishFinish(topic, store.finish(outcome.build()));
                return;
            }
            List<Clarification.Message> history = new ArrayList<>(running.history());
            Instant now = clock.instant();
            if (running.pending() != null) {
                history.add(new Clarification.Message("user", speaker, running.pending(), now));
            }
            history.add(new Clarification.Message("assistant", "需求分析师", result.json(), now));
            outcome.history = history;
            outcome.summary = result.summary();
            outcome.questions = result.questions();
            outcome.status = result.ready() ? "ready" : "in_progress";
            outcome.pending = null;
            outcome.error = null;
            // the conclusion is in the description and written back before the round ends
            Clarification done = result.ready() ? concluded(id, outcome.build()) : outcome.build();
            Clarification saved = store.finish(done);
            streams.publish(topic, "done", saved);
            streams.publish(topic, "status", Map.of("status", saved.status()));
        } catch (CancelledException e) {
            outcome.status = previous;
            outcome.error = "已停止";
            outcome.activity = snapshot(activity);
            publishFinish(topic, store.finish(outcome.build()));
        } catch (Exception e) {
            outcome.status = previous;
            outcome.error = e.getClass().getSimpleName() + ": "
                    + ClarificationProtocol.clip(String.valueOf(e.getMessage()), 300);
            outcome.activity = snapshot(activity);
            log.warn("clarification of requirement {} failed: {}", id, outcome.error);
            publishFinish(topic, store.finish(outcome.build()));
        } finally {
            watcher.cancel(false);
        }
    }

    private void publishFinish(String topic, Clarification saved) {
        streams.publish(topic, "error", Map.of("error", saved.error() == null ? "" : saved.error()));
        streams.publish(topic, "done", saved);
        streams.publish(topic, "status", Map.of("status", saved.status()));
    }

    /**
     * Writes the conclusion into the requirement (a backlog requirement becomes todo) and back to its
     * tracker, once per distinct conclusion.
     */
    private Clarification concluded(String requirementId, Clarification ready) {
        Optional<Requirement> updated = requirements.update(
                requirementId,
                r -> r.clarified(
                        ClarificationProtocol.withConclusion(r.description(), ready.summary()),
                        "backlog".equals(r.status()) ? "todo" : r.status()));
        if (updated.isEmpty()) {
            return ready;
        }
        Requirement requirement = updated.get();
        String fingerprint = sha256(ready.summary());
        if ("succeeded".equals(ready.writeBack()) && fingerprint.equals(ready.writeBackFingerprint())) {
            return ready;
        }
        Clarification.Builder next = ready.toBuilder();
        next.writeBackFingerprint = fingerprint;
        try {
            String source = switch (requirement.source()) {
                case "tapd" -> "TAPD 需求";
                case "github" -> "GitHub Issue";
                case "gitlab" -> "GitLab/工蜂 Issue";
                default -> "需求";
            };
            String body =
                    ClarificationProtocol.HEADING + "\n\n" + ready.summary().strip() + "\n\n---\n来源：" + source + " #"
                            + requirement.number() + "，由 LoopForge 基于当前描述和用户回答生成。";
            boolean written = trackers.getObject().commentClarification(requirement, body);
            next.writeBack = written ? "succeeded" : "skipped";
            next.writeBackError = null;
        } catch (TrackerException | RuntimeException e) {
            next.writeBack = "failed";
            next.writeBackError = ClarificationProtocol.clip(e.getMessage(), 300);
            log.warn("cannot write the clarification of requirement {} back: {}", requirementId, e.getMessage());
        }
        return next.build();
    }

    private List<RepositoryDefinition> repositories(Requirement requirement) {
        List<RepositoryDefinition> out = new ArrayList<>();
        for (String id : requirement.repositoryIds()) {
            catalog.repository(id)
                    .filter(r -> r.projectId() == null
                            || r.projectId().isBlank()
                            || r.projectId().equals(requirement.projectId()))
                    .ifPresent(out::add);
        }
        return out;
    }

    static String firstPrompt(Requirement r, Clarification c, List<Conversations.Checkout> checkouts) {
        StringBuilder b = new StringBuilder(SYSTEM).append('\n').append(ClarificationProtocol.FORMAT);
        b.append("\n## 需求\n编号：#").append(r.number()).append("\n标题：").append(r.title());
        b.append("\n描述：")
                .append(r.description() == null ? "（无）" : r.description().strip());
        if (r.external() != null && r.external().url() != null) {
            b.append("\n外部原单：").append(r.external().url());
        }
        if (checkouts.isEmpty()) {
            b.append("\n\n## 代码\n这条需求没有绑定代码仓库，只基于需求本身和对话澄清。");
        } else {
            b.append("\n\n## 代码（只读检出，在当前工作目录下）\n");
            for (Conversations.Checkout co : checkouts) {
                b.append("- ").append(co.name()).append("：");
                if (co.ok()) {
                    b.append("目录 ")
                            .append(co.dir())
                            .append("，")
                            .append(co.baseBranch())
                            .append(" 分支\n");
                } else {
                    b.append("检出失败（")
                            .append(ClarificationProtocol.clip(co.error(), 200))
                            .append("），不要声称读过它的代码\n");
                }
            }
        }
        if (!c.history().isEmpty()) {
            b.append("\n## 对话（按时间）\n");
            for (Clarification.Message m : c.history()) {
                b.append("assistant".equals(m.role()) ? "分析师" : "用户")
                        .append("：")
                        .append(ClarificationProtocol.clip(m.content(), 4_000))
                        .append('\n');
            }
        }
        if (c.pending() != null) {
            b.append(c.history().isEmpty() ? "\n## 对话\n" : "")
                    .append("用户：")
                    .append(c.pending())
                    .append('\n');
        }
        b.append(budget(c.analystRounds()));
        b.append("\n请基于以上信息澄清，只围绕「## 需求」本身。");
        return b.toString();
    }

    static String resumePrompt(Requirement r, Clarification c) {
        String answer = c.pending() == null ? "请基于会话里已有信息继续澄清。" : c.pending();
        return "这是用户对本轮澄清的回复。结合会话中已读过的代码和对话继续，不要重复已问过的问题。"
                + "\n范围锚点：本次只澄清「" + r.title() + "」本身，遵守首轮约定的范围规则；用户回答若带出这条需求以外的新诉求，不要沿着追问，"
                + "写进 summary 的「不在本次范围」并建议另建需求。"
                + budget(c.analystRounds())
                + "\n回复最后按约定给出 ```json 结果。\n用户：" + answer;
    }

    /** Nudges the analyst to conclude after a few rounds (upstream round budget). */
    static String budget(int rounds) {
        return rounds < 3
                ? ""
                : "\n已经澄清了 " + rounds + " 轮：除非还有不回答就无法开工的缺口，本轮直接给出结论（ready=true），" + "剩余不确定点按现有代码惯例做合理假设并写进 summary。";
    }

    private void note(String topic, List<String> activity, String line) {
        synchronized (activity) {
            activity.add(line);
            if (activity.size() > ACTIVITY_LINES) {
                activity.removeFirst();
            }
        }
        streams.publish(topic, "activity", line);
    }

    private static String line(Telemetry t) {
        if (t.message() == null || t.message().isBlank()) {
            return null;
        }
        String category = t.category() == null ? "" : t.category();
        if (!Set.of("tool", "process", "runtime", "mcp").contains(category)) {
            return null;
        }
        return ClarificationProtocol.clip(
                category + " · " + t.message().strip().lines().findFirst().orElse(""), 300);
    }

    private static List<String> snapshot(List<String> activity) {
        synchronized (activity) {
            return List.copyOf(activity);
        }
    }

    private Clarification fresh(Requirement requirement, String actor) {
        return new Clarification(
                requirement.id(),
                "new",
                null,
                List.of(),
                List.of(),
                null,
                config.sandboxProfile(),
                config.executor() == null || config.executor().isBlank()
                        ? null
                        : config.executor().strip(),
                null,
                null,
                List.of(),
                0,
                0,
                null,
                null,
                null,
                actor,
                null,
                null,
                null);
    }

    private Requirement require(String projectId, int number) {
        return requirements
                .find(projectId, number)
                .orElseThrow(() -> new io.github.loopforge4j.core.engine.RunNotFoundException(
                        "requirement #" + number + " not found"));
    }

    private CurrentUser human(Principal principal) {
        CurrentUser user = access.current(principal);
        if (user.agent() != null) {
            throw new AccessPolicy.AccessDeniedException("agents cannot clarify requirements");
        }
        return user;
    }

    private void requireEnabled() {
        if (!config.enabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "the assistant is disabled");
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(s.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
