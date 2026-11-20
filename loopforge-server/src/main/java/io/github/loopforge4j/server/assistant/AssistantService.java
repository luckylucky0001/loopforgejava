package io.github.loopforge4j.server.assistant;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.engine.Conversations;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.mcp.PlatformMcp;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.Telemetry;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.account.ProjectRole;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import io.github.loopforge4j.server.mcp.ServerPlatformMcp;
import io.github.loopforge4j.server.web.ApiException;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Conversations with the platform assistant (upstream Main Bot): every turn runs the assistant's
 * CLI in the conversation's sandbox workspace, continuing its native session, with the platform MCP
 * acting for the person speaking and the team knowledge base when connected.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final int MAX_MESSAGE = 20_000;
    private static final int ACTIVITY_LINES = 60;

    static final String BOT_BRIEF = """
            你是 LoopForge 平台助手（Main Bot），在一个持续的对话里帮团队成员完成工作。
            - 平台操作用 MCP 服务器 loopforge-platform 的工具：查需求、建需求、启动需求的 SOP、查看运行与产物、审批或打回节点、查看分析数据。你以本轮发言人的身份和权限操作，没有管理员权限。
            - 启动运行、删除、打回这类有影响的操作，先说明要做什么，得到确认后再做（用户明确要求时直接做）。
            - 当前工作目录是这个对话专属的工作区，文件在多轮对话之间保留。需要看代码或改代码时在这里 git clone、建分支、提交；不要推送到主分支，除非用户明确要求。
            - 接入了团队知识库时先检索再回答，引用附出处。
            - 回答简洁，用中文；涉及运行、需求时附上它们的 id 或编号。
            """;

    private final AssistantStore store;
    private final AssistantStreams streams;
    private final AccessPolicy access;
    private final ObjectProvider<RunEngine> engines;
    private final ServerPlatformMcp platform;
    private final LoopForgeProperties.Assistant config;
    private final Clock clock;
    private final ScheduledExecutorService watchers =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());

    public AssistantService(
            AssistantStore store,
            AssistantStreams streams,
            AccessPolicy access,
            ObjectProvider<RunEngine> engines,
            ServerPlatformMcp platform,
            LoopForgeProperties properties,
            Clock clock) {
        this.store = store;
        this.streams = streams;
        this.access = access;
        this.engines = engines;
        this.platform = platform;
        this.config = properties.assistant();
        this.clock = clock;
    }

    public record NewSession(String projectId, String title, String sandboxProfileId, String executorId) {}

    public AssistantSession create(NewSession body, Principal principal) {
        requireEnabled();
        CurrentUser user = human(principal);
        String project = blank(body.projectId());
        if (project != null) {
            access.requireProject(principal, project, ProjectRole.VIEWER);
        } else if (user.id() == null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "personal conversations need an account; choose a project");
        }
        String profile = blank(body.sandboxProfileId()) == null
                ? config.sandboxProfile()
                : body.sandboxProfileId().strip();
        access.requireIsolatedProfile(principal, profile);
        if (!engines.getObject().conversations().available(profile)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "sandbox profile '" + profile + "' is not available");
        }
        String title = blank(body.title()) == null ? "新对话" : clip(body.title().strip(), 200);
        Instant now = clock.instant();
        AssistantSession session = new AssistantSession(
                "chat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                project,
                user.id(),
                user.username(),
                title,
                profile,
                blank(body.executorId()) == null
                        ? config.executor()
                        : body.executorId().strip(),
                null,
                "idle",
                null,
                0,
                0,
                null,
                now,
                now);
        store.create(session);
        return session;
    }

    public List<AssistantSession> list(Principal principal) {
        CurrentUser user = human(principal);
        Set<String> projects = access.visibleProjects(principal);
        return store.visible(user.id(), projects);
    }

    public AssistantSession get(String id, Principal principal) {
        return readable(id, principal);
    }

    public List<AssistantMessage> messages(String id, Principal principal) {
        readable(id, principal);
        return store.messages(id, 500);
    }

    public void delete(String id, Principal principal) {
        AssistantSession session = readable(id, principal);
        CurrentUser user = human(principal);
        boolean own = session.ownerId() != null && session.ownerId().equals(user.id());
        if (!own && !user.admin()) {
            access.requireProject(principal, session.projectId(), ProjectRole.MAINTAINER);
        }
        if ("generating".equals(session.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "stop the current reply first");
        }
        store.delete(id);
        Thread.ofVirtual().start(() -> {
            try {
                engines.getObject().conversations().release(id, session.sandboxProfileId());
            } catch (Exception e) {
                log.warn("cannot remove the workspace of conversation {}: {}", id, e.toString());
            }
        });
    }

    /** Starts a turn; the reply arrives on the stream and in the message list. */
    public AssistantMessage send(String id, String content, Principal principal) {
        requireEnabled();
        AssistantSession session = readable(id, principal);
        CurrentUser user = human(principal);
        String text = content == null ? "" : content.strip();
        if (text.isEmpty() || text.length() > MAX_MESSAGE) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "message must have 1-" + MAX_MESSAGE + " characters");
        }
        // the workspace profile may have become stricter since the conversation started
        access.requireIsolatedProfile(principal, session.sandboxProfileId());
        Instant stale = clock.instant().minus(config.turnTimeout()).minus(Duration.ofMinutes(2));
        if (!store.claimTurn(id, stale)) {
            throw new ApiException(HttpStatus.CONFLICT, "the assistant is still answering");
        }
        List<AssistantMessage> history = store.messages(id, Math.max(0, config.history()));
        AssistantMessage message =
                store.append(new AssistantMessage(0, id, "user", user.username(), text, null, 0, 0, null, null));
        streams.publish(id, "message", message);
        streams.publish(id, "status", Map.of("status", "generating"));
        Thread.ofVirtual().name("assistant-" + id).start(() -> runTurn(session, user, text, history));
        return message;
    }

    public void cancel(String id, Principal principal) {
        readable(id, principal);
        if (!store.requestCancel(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "nothing to stop");
        }
    }

    private void runTurn(AssistantSession session, CurrentUser speaker, String text, List<AssistantMessage> history) {
        String id = session.id();
        Cancellation cancellation = Cancellation.create();
        List<String> activity = new ArrayList<>();
        ScheduledFuture<?> watcher = watchers.scheduleWithFixedDelay(
                () -> store.find(id)
                        .filter(s -> "cancelling".equals(s.status()))
                        .ifPresent(s -> cancellation.cancel()),
                1,
                1,
                TimeUnit.SECONDS);
        AssistantSession current = store.find(id).orElse(session);
        String cliSession = current.cliSessionId();
        long tokens = current.totalTokens();
        double cost = current.costUsd();
        String error = null;
        try {
            PlatformMcp.Binding binding =
                    platform.bindConversation(id, session.projectId(), speaker.id(), session.sandboxProfileId());
            AgentDefinition bot = AgentDefinition.builder("main-bot")
                    .name("LoopForge 助手")
                    .executor(session.executorId())
                    .build();
            String prompt = cliSession != null ? "[" + speaker.username() + "] " + text : fresh(speaker, text, history);
            Conversations.Turn turn = new Conversations.Turn(
                    id,
                    session.sandboxProfileId(),
                    bot,
                    prompt,
                    cliSession,
                    config.turnTimeout(),
                    binding.servers(),
                    binding.rules(),
                    Map.of("LOOPFORGE_CONVERSATION_ID", id));
            Conversations.Reply reply = engines.getObject()
                    .conversations()
                    .turn(
                            turn,
                            telemetry -> {
                                String line = line(telemetry);
                                if (line != null) {
                                    synchronized (activity) {
                                        activity.add(line);
                                        if (activity.size() > ACTIVITY_LINES) {
                                            activity.removeFirst();
                                        }
                                    }
                                    streams.publish(id, "activity", line);
                                }
                            },
                            cancellation);
            cliSession = reply.sessionId() != null ? reply.sessionId() : cliSession;
            tokens += reply.usage().totalTokens();
            cost += reply.usage().costUsd();
            String answer = reply.text() == null || reply.text().isBlank() ? "（没有文字回复）" : reply.text();
            AssistantMessage saved = store.append(new AssistantMessage(
                    0,
                    id,
                    reply.failed() ? "error" : "assistant",
                    "LoopForge 助手",
                    clip(answer, 200_000),
                    reply.model(),
                    reply.usage().totalTokens(),
                    reply.usage().costUsd(),
                    snapshot(activity),
                    null));
            streams.publish(id, "message", saved);
        } catch (CancelledException e) {
            error = "已停止";
            streams.publish(id, "message", store.append(errorMessage(id, "已停止回复", activity)));
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + clip(String.valueOf(e.getMessage()), 300);
            log.warn("conversation {} turn failed: {}", id, error);
            streams.publish(id, "message", store.append(errorMessage(id, "回复失败：" + error, activity)));
        } finally {
            watcher.cancel(false);
            store.finishTurn(current.with("idle", cliSession, error, tokens, cost, null));
            streams.publish(id, "status", Map.of("status", "idle"));
        }
    }

    /** The whole recent conversation, for a turn that cannot continue a CLI session. */
    private static String fresh(CurrentUser speaker, String text, List<AssistantMessage> history) {
        StringBuilder b = new StringBuilder(BOT_BRIEF).append('\n');
        if (!history.isEmpty()) {
            b.append("<conversation>\n");
            for (AssistantMessage m : history) {
                if ("error".equals(m.role())) {
                    continue;
                }
                b.append('[')
                        .append("assistant".equals(m.role()) ? "助手" : m.author())
                        .append("] ")
                        .append(clip(m.content(), 4000))
                        .append('\n');
            }
            b.append("</conversation>\n");
        }
        b.append("最新消息（来自 ").append(speaker.username()).append("）：\n").append(text);
        return b.toString();
    }

    /** What the person sees of the assistant's work: tool calls and commands, not raw output. */
    private static String line(Telemetry t) {
        if (t.message() == null || t.message().isBlank()) {
            return null;
        }
        String category = t.category() == null ? "" : t.category();
        if (!Set.of("tool", "process", "hook", "runtime", "mcp").contains(category)) {
            return null;
        }
        return clip(category + " · " + t.message().strip().lines().findFirst().orElse(""), 300);
    }

    private static List<String> snapshot(List<String> activity) {
        synchronized (activity) {
            return List.copyOf(activity);
        }
    }

    private static AssistantMessage errorMessage(String id, String text, List<String> activity) {
        return new AssistantMessage(0, id, "error", "LoopForge 助手", text, null, 0, 0, snapshot(activity), null);
    }

    private AssistantSession readable(String id, Principal principal) {
        AssistantSession session = store.find(id).orElseThrow(() -> new RunNotFoundException("conversation not found"));
        CurrentUser user = human(principal);
        if (session.projectId() == null) {
            if (session.ownerId() == null || !session.ownerId().equals(user.id())) {
                throw new RunNotFoundException("conversation not found");
            }
        } else if (!access.canSee(principal, session.projectId())) {
            throw new RunNotFoundException("conversation not found");
        }
        return session;
    }

    /** Agents (including the assistant itself) do not hold conversations. */
    private CurrentUser human(Principal principal) {
        CurrentUser user = access.current(principal);
        if (user.agent() != null) {
            throw new AccessPolicy.AccessDeniedException("agents cannot use conversations");
        }
        return user;
    }

    private void requireEnabled() {
        if (!config.enabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "the assistant is disabled");
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
