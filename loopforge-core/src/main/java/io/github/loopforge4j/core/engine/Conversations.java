package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.TokenUsage;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.executor.AgentExecutor;
import io.github.loopforge4j.core.runtime.executor.AgentInvocation;
import io.github.loopforge4j.core.runtime.executor.AgentResult;
import io.github.loopforge4j.core.runtime.executor.ExecutorException;
import io.github.loopforge4j.core.runtime.git.GitException;
import io.github.loopforge4j.core.runtime.git.GitWorkspace;
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
     * @param responsible the person speaking: their own executor and Git credentials apply
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
            Map<String, String> environment,
            String responsible) {

        public Turn(
                String conversationId,
                String sandboxProfileId,
                AgentDefinition bot,
                String prompt,
                String resumeSessionId,
                Duration timeout,
                List<McpServerDefinition> extraServers,
                List<RuleDefinition> extraRules,
                Map<String, String> environment) {
            this(
                    conversationId,
                    sandboxProfileId,
                    bot,
                    prompt,
                    resumeSessionId,
                    timeout,
                    extraServers,
                    extraRules,
                    environment,
                    null);
        }

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
        AgentAssets assets = AgentAssets.resolve(turn.bot(), engine.catalog(), null, turn.responsible())
                .withGitHubToken(githubToken(turn.responsible()))
                .with(turn.extraServers(), turn.extraRules());
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
        // the speaker's own Git credentials, never the deployment's: they can read this environment
        io.github.loopforge4j.core.runtime.git.GitEnvironment.Binding gitBinding =
                engine.personalGitBinding(sandbox, turn.responsible());
        Map<String, String> environment = new java.util.HashMap<>(gitBinding.environment());
        environment.putAll(turn.environment());
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
                environment,
                compiled,
                turn.responsible());
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
            gitBinding.close();
            if (compiled != null) {
                try {
                    compiled.cleanup();
                } catch (IOException e) {
                    log.warn("cannot remove runtime files of {}: {}", invocationId, e.toString());
                }
            }
        }
    }

    /** A repository checked out read-only into a conversation workspace, or why it could not be. */
    public record Checkout(String repositoryId, String name, String dir, String baseBranch, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Checks repositories out read-only into the conversation's workspace at the tip of their base
     * branch (requirement clarification reads the code it asks about). A checkout that fails is
     * reported, not thrown: the conversation goes on without that repository.
     *
     * @param baseBranch branch to read, {@code null} for each repository's default branch
     * @param responsible whose Git credentials (over the deployment's) read the repositories
     */
    public List<Checkout> checkout(
            String conversationId,
            String sandboxProfileId,
            List<RepositoryDefinition> repositories,
            String baseBranch,
            String responsible,
            Cancellation cancellation)
            throws IOException, CancelledException {
        if (repositories.isEmpty()) {
            return List.of();
        }
        SandboxProvider provider = engine.sandboxProvider(sandboxProfileId)
                .orElseThrow(() ->
                        new IllegalArgumentException("sandbox profile '" + sandboxProfileId + "' is not available"));
        Sandbox sandbox = provider.acquire(workspaceId(conversationId));
        java.util.Set<String> dirs = new java.util.HashSet<>();
        List<RepositoryContext> contexts = new java.util.ArrayList<>();
        for (RepositoryDefinition repository : repositories) {
            RepositoryContext rc = RepositoryContext.of(repository, baseBranch).withMode(true, false, false);
            String base = RunEngine.directoryName(rc);
            String dir = base;
            for (int n = 2; !dirs.add(dir); n++) {
                dir = base + "-" + n;
            }
            contexts.add(rc.withWorkspaceDir(dir));
        }
        io.github.loopforge4j.core.runtime.ManagedFiles files =
                new io.github.loopforge4j.core.runtime.ManagedFiles(sandbox.workspace());
        // The agent works in these checkouts between rounds and could plant repository-local git
        // configuration (fsmonitor, hooks, helpers) that would run with the credentials of the next
        // fetch: every round clones afresh before any git command touches them.
        for (RepositoryContext rc : contexts) {
            files.delete(sandbox.workspace().resolve(rc.workspaceDir()));
        }
        // the same for the git commands' private HOME (a planted ~/.gitconfig)
        java.nio.file.Path gitHome = sandbox.privateHome("git");
        if (gitHome != null) {
            files.delete(gitHome);
        }
        List<Checkout> out = new java.util.ArrayList<>();
        try (io.github.loopforge4j.core.runtime.git.GitEnvironment.Binding gitBinding =
                engine.gitBinding(sandbox, contexts, responsible)) {
            GitWorkspace git = new GitWorkspace(sandbox, gitBinding.environment(), cancellation);
            for (RepositoryContext rc : contexts) {
                try {
                    git.prepare(rc);
                    git.syncReadOnly(rc);
                    out.add(new Checkout(rc.repositoryId(), rc.name(), rc.workspaceDir(), rc.baseBranch(), null));
                } catch (GitException e) {
                    out.add(new Checkout(
                            rc.repositoryId(), rc.name(), rc.workspaceDir(), rc.baseBranch(), e.getMessage()));
                }
            }
        }
        return out;
    }

    /** The conversation's workspace root, if its sandbox profile is available here. */
    public java.util.Optional<java.nio.file.Path> workspace(String conversationId, String sandboxProfileId)
            throws IOException {
        var provider = engine.sandboxProvider(sandboxProfileId);
        if (provider.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                provider.get().acquire(workspaceId(conversationId)).workspace());
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

    /** A conversation only ever gets its speaker's own GitHub token, never the deployment's. */
    private String githubToken(String responsible) {
        if (responsible == null) {
            return null;
        }
        var credential =
                engine.gitCredentials().personalCredentials(responsible).https().get("github.com");
        return credential == null ? null : credential.token();
    }
}
