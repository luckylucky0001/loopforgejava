package io.github.loopforge4j.core.runtime.materialize;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AgentHook;
import io.github.loopforge4j.core.domain.ExecutionScope;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.ScriptLanguage;
import io.github.loopforge4j.core.domain.SkillDefinition;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.mcp.GateMcpPackage;
import io.github.loopforge4j.core.runtime.ManagedFiles;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandbox;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compiles a role into workspace files for one invocation (upstream {@code materializeInvocation}):
 * system instructions with the role's catalog rules, skills (SKILL.md trees, a Claude Code plugin
 * manifest), hook scripts with an observing wrapper, and the MCP allowlist including the gate
 * server. Repository-local or user-level CLI configuration is never consulted.
 */
public final class AgentMaterializer {

    public static final String AGENTS_DIR = "agents";
    public static final String RUNTIME_DIR = "runtime";
    public static final String GATE_JAR = "bin/loopforge-gate-mcp.jar";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}");
    private static final Pattern SECRET = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]{1,100})}");
    private static final ReentrantLock[] LOCKS = new ReentrantLock[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    private AgentMaterializer() {}

    /**
     * One invocation to compile.
     *
     * @param gateResultPath workspace-relative gate result file, {@code null} for no gate server
     * @param javaBinary {@code java} launcher the sandbox can run (for the gate server)
     * @param secrets resolves {@code ${secret:NAME}} placeholders of MCP servers; {@code null} result
     *     means the secret is not configured
     * @param repositoryDirs workspace-relative repository checkouts, for repository-scoped hooks
     */
    public record Request(
            Path workspace,
            String invocationId,
            String executorKind,
            AgentDefinition agent,
            AgentAssets assets,
            String gateResultPath,
            Path javaBinary,
            UnaryOperator<String> secrets,
            List<String> repositoryDirs) {

        public Request {
            assets = assets == null ? AgentAssets.NONE : assets;
            secrets = secrets == null ? name -> null : secrets;
            repositoryDirs = repositoryDirs == null ? List.of() : List.copyOf(repositoryDirs);
        }
    }

    public static CompiledAgent materialize(Request request) throws IOException {
        String agentId = safeSegment(request.agent().id());
        ManagedFiles files = new ManagedFiles(request.workspace());
        Path control = files.root().resolve(LocalSandbox.CONTROL_DIR);
        Path agentDir = control.resolve(AGENTS_DIR).resolve(agentId);
        Path runtimeDir = files.reset(control.resolve(RUNTIME_DIR).resolve(safeSegment(request.invocationId())));
        ReentrantLock lock = LOCKS[Math.floorMod(agentDir.hashCode(), LOCKS.length)];
        lock.lock();
        try {
            files.directory(agentDir);
            List<CompiledAgent.CompiledSkill> skills = writeSkills(files, agentDir, request);
            List<CompiledAgent.CompiledHook> hooks = writeHooks(files, agentDir, request.agent());
            List<McpServerDefinition> servers = new ArrayList<>();
            for (McpServerDefinition server : request.assets().mcpServers()) {
                servers.add(resolveSecrets(server, request.secrets()));
            }
            if (request.gateResultPath() != null) {
                files.directory(control.resolve("bin"));
                servers.add(GateMcpPackage.materialize(
                        control.resolve(GATE_JAR),
                        request.javaBinary() == null ? GateMcpPackage.currentJava() : request.javaBinary(),
                        files.root().resolve(request.gateResultPath())));
            }
            Path mcpConfig = runtimeDir.resolve("mcp.json");
            files.write(mcpConfig, Json.write(Map.of("mcpServers", mcpEntries(servers))), false);
            Path hookSettings = null;
            if (!hooks.isEmpty()) {
                hookSettings = runtimeDir.resolve("settings.json");
                files.write(hookSettings, Json.write(hookSettings(hooks)), false);
            }
            String instructions =
                    systemInstructions(request.agent(), request.assets().rules(), skills, servers);
            files.write(agentDir.resolve("agent-system.md"), instructions, false);
            Path hookOutput = runtimeDir.resolve("hooks.ndjson");
            Map<String, String> env = new LinkedHashMap<>();
            env.put("LOOPFORGE_HOOK_OUTPUT", hookOutput.toString());
            if (!request.repositoryDirs().isEmpty()) {
                env.put("LOOPFORGE_REPOSITORY_DIRS", String.join("\n", request.repositoryDirs()));
            }
            return new CompiledAgent(
                    agentDir,
                    runtimeDir,
                    instructions,
                    servers,
                    mcpConfig,
                    hookSettings,
                    hooks,
                    skills,
                    hookOutput,
                    env);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ skills

    private static List<CompiledAgent.CompiledSkill> writeSkills(ManagedFiles files, Path agentDir, Request request)
            throws IOException {
        Path skillsDir = agentDir.resolve("skills");
        Set<String> keep = new HashSet<>();
        List<CompiledAgent.CompiledSkill> compiled = new ArrayList<>();
        for (SkillDefinition skill : request.assets().skills()) {
            String id = safeSegment(skill.id());
            keep.add(id);
            Path root = skillsDir.resolve(id);
            String body = skill.files().getOrDefault("SKILL.md", skill.content());
            String description =
                    skill.description() == null ? "" : skill.description().strip();
            body = SkillMarkdown.ensureFrontmatter(body, id, description);
            String parsed = SkillMarkdown.description(body);
            files.write(root.resolve("SKILL.md"), body, false);
            for (Map.Entry<String, String> file : skill.files().entrySet()) {
                if (file.getKey().equals("SKILL.md")) {
                    continue;
                }
                String relative = SkillMarkdown.checkPath(file.getKey());
                files.write(root.resolve(relative), file.getValue(), relative.startsWith("scripts/"));
            }
            compiled.add(new CompiledAgent.CompiledSkill(id, id, parsed.isBlank() ? description : parsed, root));
        }
        removeStale(files, skillsDir, keep);
        Path manifest = agentDir.resolve(".claude-plugin").resolve("plugin.json");
        if (compiled.isEmpty()) {
            files.delete(manifest.getParent());
        } else {
            Map<String, Object> plugin = new LinkedHashMap<>();
            plugin.put("name", "loopforge-" + safeSegment(request.agent().id()).toLowerCase(java.util.Locale.ROOT));
            plugin.put("version", "1.0.0");
            plugin.put("description", "Agent-scoped LoopForge skills");
            plugin.put("author", Map.of("name", "LoopForge4J"));
            files.write(manifest, Json.write(plugin), false);
        }
        return compiled;
    }

    // ------------------------------------------------------------------ hooks

    private static List<CompiledAgent.CompiledHook> writeHooks(ManagedFiles files, Path agentDir, AgentDefinition agent)
            throws IOException {
        Path hooksDir = agentDir.resolve("hooks");
        Set<String> keep = new HashSet<>();
        List<CompiledAgent.CompiledHook> compiled = new ArrayList<>();
        for (AgentHook hook : agent.hooks()) {
            String id = safeSegment(hook.id());
            boolean python = hook.language() == ScriptLanguage.PYTHON;
            String scriptName = id + (python ? ".py" : ".sh");
            String wrapperName = id + "-run.sh";
            keep.add(scriptName);
            keep.add(wrapperName);
            String body = hook.script().endsWith("\n") ? hook.script() : hook.script() + "\n";
            Path script = hooksDir.resolve(scriptName);
            files.write(script, body, true);
            Path wrapper = hooksDir.resolve(wrapperName);
            files.write(wrapper, HookWrapper.script(hook, python ? "python3" : "sh", script), true);
            compiled.add(new CompiledAgent.CompiledHook(
                    hook.id(),
                    hook.event(),
                    hook.matcher(),
                    "sh " + HookWrapper.quote(wrapper.toString()),
                    hook.timeoutSec()));
        }
        removeStale(files, hooksDir, keep);
        return compiled;
    }

    /** Claude Code / CodeBuddy {@code --settings} document. */
    static Map<String, Object> hookSettings(List<CompiledAgent.CompiledHook> hooks) {
        Map<String, List<Object>> events = new LinkedHashMap<>();
        for (CompiledAgent.CompiledHook hook : hooks) {
            Map<String, Object> group = new LinkedHashMap<>();
            if (hook.matcher() != null) {
                group.put("matcher", hook.matcher());
            }
            group.put(
                    "hooks",
                    List.of(Map.of("type", "command", "command", hook.command(), "timeout", hook.timeoutSec())));
            events.computeIfAbsent(hook.event(), e -> new ArrayList<>()).add(group);
        }
        return Map.of("hooks", events);
    }

    // ------------------------------------------------------------------ MCP

    /** {@code mcpServers} entries in the Claude Code format (upstream {@code mcpConfigEntries}). */
    public static Map<String, Object> mcpEntries(List<McpServerDefinition> servers) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (McpServerDefinition server : servers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (server.http()) {
                entry.put("type", "http");
                entry.put("url", server.url());
                if (!server.headers().isEmpty()) {
                    entry.put("headers", server.headers());
                }
            } else {
                entry.put("command", server.command());
                entry.put("args", server.args());
                if (server.workingDirectory() != null
                        && !server.workingDirectory().isBlank()) {
                    entry.put("cwd", server.workingDirectory());
                }
                if (!server.environment().isEmpty()) {
                    entry.put("env", server.environment());
                }
            }
            entries.put(server.id(), entry);
        }
        return entries;
    }

    static McpServerDefinition resolveSecrets(McpServerDefinition server, UnaryOperator<String> secrets) {
        Map<String, String> env = new LinkedHashMap<>();
        server.environment().forEach((k, v) -> env.put(k, resolve(server, v, secrets)));
        Map<String, String> headers = new LinkedHashMap<>();
        server.headers().forEach((k, v) -> headers.put(k, resolve(server, v, secrets)));
        List<String> args =
                server.args().stream().map(a -> resolve(server, a, secrets)).toList();
        return new McpServerDefinition(
                server.id(),
                server.name(),
                server.description(),
                server.transport(),
                server.command(),
                args,
                server.url() == null ? null : resolve(server, server.url(), secrets),
                env,
                headers,
                server.workingDirectory(),
                server.timeoutSec(),
                server.updatedAt());
    }

    private static String resolve(McpServerDefinition server, String value, UnaryOperator<String> secrets) {
        if (value == null || !value.contains("${secret:")) {
            return value;
        }
        Matcher m = SECRET.matcher(value);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String secret = secrets.apply(m.group(1));
            if (secret == null || secret.isEmpty()) {
                throw new IllegalArgumentException(
                        "MCP server " + server.id() + ": secret '" + m.group(1) + "' is not configured");
            }
            m.appendReplacement(b, Matcher.quoteReplacement(secret));
        }
        m.appendTail(b);
        return b.toString();
    }

    // ------------------------------------------------------------------ instructions

    static String systemInstructions(
            AgentDefinition agent,
            List<RuleDefinition> rules,
            List<CompiledAgent.CompiledSkill> skills,
            List<McpServerDefinition> servers) {
        StringBuilder b = new StringBuilder("# ").append(agent.name()).append("\n");
        if (!rules.isEmpty()) {
            b.append("\n## Rules\n");
            for (RuleDefinition rule : rules) {
                b.append("\n### ")
                        .append(rule.name())
                        .append('\n')
                        .append(rule.content().strip())
                        .append('\n');
            }
        }
        if (!skills.isEmpty()) {
            b.append("\n## Selected Skills\n\n")
                    .append(
                            "Only the skills linked to this Agent are available. Ignore repository or user-level Agent CLI configuration.\n\n")
                    .append(
                            "If this CLI exposes a native Skill tool or slash command, invoke the skill by `name` below before doing the work. ")
                    .append(
                            "Otherwise read SKILL.md, then follow it using the skill root for relative `scripts/`, `assets/`, and `references/` paths.\n");
            for (CompiledAgent.CompiledSkill skill : skills) {
                b.append("\n- name: `")
                        .append(skill.name())
                        .append("` (id: ")
                        .append(skill.id())
                        .append(")\n")
                        .append("  description: ")
                        .append(skill.description())
                        .append('\n')
                        .append("  skill.md: `")
                        .append(skill.root().resolve("SKILL.md"))
                        .append("`\n")
                        .append("  root: `")
                        .append(skill.root())
                        .append("`\n");
            }
        }
        if (!servers.isEmpty()) {
            b.append("\n## MCP 怎么用\n\n本节点的 MCP 配置已按 Agent allowlist 严格注入：")
                    .append(String.join(
                            "、", servers.stream().map(McpServerDefinition::id).toList()))
                    .append("。它们会以原生工具出现（名称常为 mcp__<server>__*），直接调用原生工具；")
                    .append("不要用 curl 或脚本绕过 MCP 访问这些服务。\n");
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Writes a control file below the workspace (e.g. an agent CLI's MCP configuration in its private
     * HOME) with the same symlink checks as role files.
     */
    public static void writeControlFile(Path workspace, Path file, String content) throws IOException {
        new ManagedFiles(workspace).write(file, content, false);
    }

    public static void writeControlFile(Path workspace, Path file, String content, boolean executable)
            throws IOException {
        new ManagedFiles(workspace).write(file, content, executable);
    }

    private static void removeStale(ManagedFiles files, Path dir, Set<String> keep) throws IOException {
        files.verify(dir);
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                if (!keep.contains(child.getFileName().toString())) {
                    files.delete(child);
                }
            }
        }
    }

    /** File-name-safe form of an id; ids are validated, this is defence in depth. */
    static String safeSegment(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid id for a control path: " + id);
        }
        return id.replace(':', '_');
    }

    /** Validates a skill file path; throws {@link IllegalArgumentException}. */
    public static String checkSkillPath(String path) {
        return SkillMarkdown.checkPath(path);
    }

    /** Whether an id may name an agent, skill, hook or MCP server directory. */
    public static boolean validId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    /** The role's hooks grouped as {@code ExecutionScope}; used by validation. */
    static boolean repositoryScoped(AgentHook hook) {
        return hook.effectiveScope() == ExecutionScope.REPOSITORIES;
    }
}
