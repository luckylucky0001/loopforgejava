package io.github.loopforge4j.core.runtime.materialize;

import io.github.loopforge4j.core.domain.McpServerDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A role compiled into one invocation's workspace files (upstream {@code compiledConfig}).
 *
 * @param agentDir shared, deterministic role files: system instructions, skills, hook scripts
 * @param runtimeDir per-invocation files: MCP config with resolved secrets, hook settings, hook log;
 *     deleted by {@link #cleanup()}
 * @param systemInstructions rules, skills and MCP guidance sent ahead of the task
 * @param mcpServers effective servers including the gate server, secrets resolved
 * @param mcpConfig {@code {"mcpServers": ...}} document (Claude Code / CodeBuddy format)
 * @param hookSettings Claude-style settings file with the role's hooks, or {@code null}
 * @param hooks compiled hooks
 * @param skills materialized skills
 * @param hookOutput NDJSON file the hook wrappers append observations to
 * @param environment variables the CLI process needs (hook log, repository list)
 */
public record CompiledAgent(
        Path agentDir,
        Path runtimeDir,
        String systemInstructions,
        List<McpServerDefinition> mcpServers,
        Path mcpConfig,
        Path hookSettings,
        List<CompiledHook> hooks,
        List<CompiledSkill> skills,
        Path hookOutput,
        Map<String, String> environment) {

    public CompiledAgent {
        mcpServers = List.copyOf(mcpServers);
        hooks = List.copyOf(hooks);
        skills = List.copyOf(skills);
        environment = Map.copyOf(environment);
    }

    /** A hook as the CLI runs it. */
    public record CompiledHook(String id, String event, String matcher, String command, int timeoutSec) {}

    /** A skill below {@link #agentDir()}{@code /skills}. */
    public record CompiledSkill(String id, String name, String description, Path root) {}

    public boolean hasSkills() {
        return !skills.isEmpty();
    }

    /** Removes the per-invocation files (they may hold resolved MCP secrets). */
    public void cleanup() throws IOException {
        if (!Files.exists(runtimeDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(runtimeDir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
