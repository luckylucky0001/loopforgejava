package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.domain.ExecutorDefinition;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executor definitions by id and adapters by kind. Built-in definitions mirror upstream
 * {@code BuiltInExecutors}; operators can replace them (e.g. to point at another binary path).
 */
public final class ExecutorRegistry {

    private final Map<String, ExecutorDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, AgentExecutor> adapters = new LinkedHashMap<>();

    private static final Map<String, String> AGENT_CLI_NAMES = Map.of(
            AgentCliExecutor.OPENCODE, "OpenCode",
            AgentCliExecutor.CURSOR, "Cursor",
            AgentCliExecutor.ANTIGRAVITY, "Antigravity",
            AgentCliExecutor.KIMI, "Kimi Code",
            AgentCliExecutor.PI, "Pi",
            AgentCliExecutor.DEEPSEEK, "DeepSeek Harness",
            AgentCliExecutor.ZCODE, "ZCode");

    public static List<ExecutorDefinition> builtInDefinitions() {
        List<ExecutorDefinition> definitions = new java.util.ArrayList<>(coreDefinitions());
        for (String kind : AgentCliExecutor.KINDS) {
            definitions.add(new ExecutorDefinition(
                    kind,
                    AGENT_CLI_NAMES.get(kind),
                    kind,
                    AgentCliExecutor.binary(kind),
                    List.of(),
                    "agent-cli-stream",
                    null,
                    null));
        }
        return List.copyOf(definitions);
    }

    private static List<ExecutorDefinition> coreDefinitions() {
        return List.of(
                new ExecutorDefinition(
                        "claude",
                        "Claude Code",
                        ClaudeCodeExecutor.KIND,
                        "claude",
                        List.of("-p", "--output-format", "stream-json"),
                        "stream-json",
                        null,
                        null),
                new ExecutorDefinition(
                        "codex",
                        "Codex",
                        CodexExecutor.KIND,
                        "codex",
                        List.of("exec", "--json", "--sandbox", "workspace-write", "--skip-git-repo-check"),
                        "jsonl",
                        null,
                        null),
                new ExecutorDefinition(
                        "codebuddy",
                        "CodeBuddy",
                        CodeBuddyExecutor.KIND,
                        "codebuddy",
                        List.of("-p", "--output-format", "stream-json", "-y", "--setting-sources", "none"),
                        "stream-json",
                        null,
                        null),
                new ExecutorDefinition(
                        "mock",
                        "Mock (deterministic, no model)",
                        MockAgentExecutor.KIND,
                        null,
                        List.of(),
                        "builtin",
                        null,
                        null));
    }

    /** Registry with all built-in definitions and adapters. */
    public static ExecutorRegistry withBuiltIns(CredentialProvider credentials) {
        ExecutorRegistry registry = new ExecutorRegistry();
        builtInDefinitions().forEach(registry::define);
        registry.register(new ClaudeCodeExecutor(credentials));
        registry.register(new CodexExecutor(credentials));
        registry.register(new CodeBuddyExecutor(credentials));
        registry.register(new MockAgentExecutor());
        for (String kind : AgentCliExecutor.KINDS) {
            registry.register(new AgentCliExecutor(kind, credentials));
        }
        return registry;
    }

    public ExecutorRegistry define(ExecutorDefinition definition) {
        definitions.put(definition.id(), definition);
        return this;
    }

    public ExecutorRegistry register(AgentExecutor adapter) {
        adapters.put(adapter.kind(), adapter);
        return this;
    }

    public Optional<ExecutorDefinition> definition(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public Collection<ExecutorDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    public Optional<AgentExecutor> adapter(String kind) {
        return Optional.ofNullable(adapters.get(kind));
    }
}
