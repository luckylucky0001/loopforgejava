package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.TokenUsage;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.executor.AgentExecutor;
import io.github.loopforge4j.core.runtime.executor.AgentInvocation;
import io.github.loopforge4j.core.runtime.executor.AgentResult;
import io.github.loopforge4j.core.runtime.executor.ExecutorException;
import io.github.loopforge4j.core.runtime.materialize.AgentAssets;
import io.github.loopforge4j.core.runtime.materialize.AgentMaterializer;
import io.github.loopforge4j.core.runtime.materialize.CompiledAgent;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import io.github.loopforge4j.core.runtime.sandbox.SandboxProvider;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conversation turns (upstream Main Bot workspace mode): the bot is a CLI agent that keeps one
 * sandbox workspace per conversation across turns and continues its native CLI session. A turn is
 * not a run: no SOP graph, no gates, no queueing, but the same executors, sandboxes, role
 * materialization (skills, MCP servers, hooks) and pricing.
 */
public final class Conversations {

    private static final Logger log = LoggerFactory.getLogger(Conversations.class);

    /**
     * @param conversationId stable id; the workspace is {@code conv-<id>} of the sandbox profile
     * @param bot the role the assistant plays (executor, model, prompt, rules, skills, MCP)
     * @param prompt the turn's prompt (the whole history when there is no session to continue)
     * @param resumeSessionId the CLI session to continue, {@code null} for a fresh one
     * @param extraServers platform-served MCP servers for this turn, credentials included
     * @param extraRules usage rules that come with them
     */
    public record Turn(
            String conversationId,
            String sandboxProfileId,
            AgentDefinition bot,
            String prompt,
            String resumeSessionId,
            Duration timeout,
            List<McpServerDefinition> extraServers,
            List<RuleDefinition> extraRules,
            Map<String, String> environment) {

        public Turn {
            extraServers = extraServers == null ? List.of() : List.copyOf(extraServers);
            extraRules = extraRules == null ? List.of() : List.copyOf(extraRules);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }
    }

    /** The reply of one turn, priced like a node invocation. */
    public record Reply(String text, String sessionId, String model, TokenUsage usage, int toolCalls, boolean failed) {}

    private final RunEngine engine;
    private final AtomicLong sequence = new AtomicLong();

    Conversations(RunEngine engine) {
        this.engine = engine;
    }

    /** Whether conversations can use the sandbox profile on this engine. */
    public boolean available(String sandboxProfileId) {
        return engine.sandboxProvider(sandboxProfileId).isPresent();
    }

    public static String workspaceId(String conversationId) {
        return "conv-" + conversationId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * Runs one turn to completion.
     *
     * @throws IllegalArgumentException when the sandbox profile or executor is not available
     */
    public Reply turn(Turn turn, EventSink sink, Cancellation cancellation)
            throws IOException, ExecutorException, CancelledException {
        SandboxProvider provider = engine.sandboxProvider(turn.sandboxProfileId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "sandbox profile '" + turn.sandboxProfileId() + "' is not available"));
        Sandbox sandbox = provider.acquire(workspaceId(turn.conversationId()));
        ExecutorDefinition executor = executor(turn.bot());
        AgentExecutor adapter = engine.executors()
                .adapter(executor.kind())
                .orElseThrow(
                        () -> new IllegalArgumentException("executor kind '" + executor.kind() + "' is not supported"));
        AgentAssets assets =
                AgentAssets.resolve(turn.bot(), engine.catalog()).with(turn.extraServers(), turn.extraRules());
        String invocationId = workspaceId(turn.conversationId()) + "-" + System.currentTimeMillis() + "-"
                + sequence.incrementAndGet();
        CompiledAgent compiled = adapter.materializes()
                ? AgentMaterializer.materialize(new AgentMaterializer.Request(
                        sandbox.workspace(),
                        invocationId,
                        executor.kind(),
                        turn.bot(),
                        assets,
                        null,
                        engine.gateMcpJava(),
                        engine.mcpSecrets(),
                        List.of()))
                : null;
        AgentInvocation invocation = new AgentInvocation(
                turn.conversationId(),
                "chat",
                1,
                invocationId,
                executor,
                turn.bot(),
                turn.prompt(),
                turn.resumeSessionId(),
                null,
                List.of(),
                turn.timeout(),
                turn.environment(),
                compiled);
        try {
            AgentResult result = adapter.execute(invocation, sandbox, sink, cancellation);
            return reply(executor, result, false);
        } catch (ExecutorException e) {
            if (e.partial() != null && e.partial().sessionId() != null) {
                // keep the session: the next turn can continue where the CLI stopped
                return reply(executor, e.partial(), true);
            }
            throw e;
        } finally {
            if (compiled != null) {
                try {
                    compiled.cleanup();
                } catch (IOException e) {
                    log.warn("cannot remove runtime files of {}: {}", invocationId, e.toString());
                }
            }
        }
    }

    /** Deletes the conversation's workspace. */
    public void release(String conversationId, String sandboxProfileId) throws IOException {
        var provider = engine.sandboxProvider(sandboxProfileId);
        if (provider.isPresent()) {
            provider.get().release(workspaceId(conversationId));
        }
    }

    private Reply reply(ExecutorDefinition executor, AgentResult result, boolean failed) {
        String model = result.model() != null ? result.model() : executor.model();
        TokenUsage usage = engine.pricing().price(model, result.usage(), "codex".equals(executor.kind()));
        return new Reply(
                result.output(), result.sessionId(), model, usage, result.toolCalls(), failed || result.failed());
    }

    private ExecutorDefinition executor(AgentDefinition bot) {
        String id = bot.executorId() != null && !bot.executorId().isBlank()
                ? bot.executorId()
                : engine.settings().defaultExecutorId();
        ExecutorDefinition definition = engine.executors()
                .definition(id)
                .orElseThrow(() -> new IllegalArgumentException("executor '" + id + "' not found"));
        if (bot.model() != null && !bot.model().isBlank()
                || bot.effort() != null && !bot.effort().isBlank()) {
            definition = definition.withModel(
                    bot.model() != null && !bot.model().isBlank() ? bot.model() : definition.model(),
                    bot.effort() != null && !bot.effort().isBlank() ? bot.effort() : definition.effort());
        }
        return definition;
    }
}
