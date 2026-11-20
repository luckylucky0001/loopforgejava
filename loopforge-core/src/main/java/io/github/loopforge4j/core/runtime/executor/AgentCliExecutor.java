package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.Telemetry;
import io.github.loopforge4j.core.runtime.materialize.AgentMaterializer;
import io.github.loopforge4j.core.runtime.materialize.CompiledAgent;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The agent CLIs beyond Claude Code, Codex and CodeBuddy, each in its native headless mode (upstream
 * {@code agentCLIProvider} / {@code AgentCLICommand}). The role's MCP servers are written in the
 * CLI's own format into its private HOME, the native stream is translated into Claude-style
 * stream-json and parsed by {@link AnthropicStreamParser}.
 *
 * <p>These CLIs take the prompt as an argument. The argument only points at the task book file
 * (ASCII), so non-UTF-8 locales cannot mangle it and it stays out of {@code ps}.
 */
public final class AgentCliExecutor extends CliAgentExecutor {

    public static final String OPENCODE = "opencode";
    public static final String CURSOR = "cursor";
    public static final String ANTIGRAVITY = "agy";
    public static final String KIMI = "kimi";
    public static final String PI = "pi";
    public static final String DEEPSEEK = "dsh";
    public static final String ZCODE = "zcode";
    public static final List<String> KINDS = List.of(OPENCODE, CURSOR, ANTIGRAVITY, KIMI, PI, DEEPSEEK, ZCODE);

    /** HOME-relative MCP configuration file of each CLI. */
    static final Map<String, String> MCP_PATHS = Map.of(
            OPENCODE, ".config/opencode/opencode.json",
            CURSOR, ".cursor/mcp.json",
            ANTIGRAVITY, ".gemini/config/mcp_config.json",
            KIMI, ".kimi-code/mcp.json",
            PI, ".pi/agent/mcp.json",
            DEEPSEEK, ".dsh/agentflow-mcp.json",
            ZCODE, ".zcode/cli/config.json");

    private static final String ZCODE_DISALLOWED = "AskUserQuestion EnterPlanMode ExitPlanMode";

    private final String kind;

    public AgentCliExecutor(String kind, CredentialProvider credentials) {
        super(credentials);
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("unknown agent CLI " + kind);
        }
        this.kind = kind;
    }

    @Override
    public String kind() {
        return kind;
    }

    /** Default binary name (upstream {@code AgentCLIBinary}). */
    public static String binary(String kind) {
        return CURSOR.equals(kind) ? "cursor-agent" : kind;
    }

    static String pointerPrompt(String invocationId) {
        return "Read the complete task book in `" + promptFile(Path.of(""), invocationId)
                + "` first and follow it exactly. It contains your system instructions, the task, the deliverables"
                + " and how to submit this node's terminal result.";
    }

    @Override
    protected String stdinPrompt(AgentInvocation invocation, String prompt) {
        return null;
    }

    @Override
    protected List<String> arguments(AgentInvocation invocation) {
        return arguments(invocation, (Path) null);
    }

    @Override
    protected List<String> arguments(AgentInvocation invocation, Sandbox sandbox) {
        Path home = sandbox == null ? null : sandbox.privateHome(kind);
        return arguments(
                invocation,
                invocation.compiled() == null || home == null ? null : home.resolve(MCP_PATHS.get(DEEPSEEK)));
    }

    private List<String> arguments(AgentInvocation invocation, Path dshPatch) {
        String binary = invocation.executor().binary() == null
                        || invocation.executor().binary().isBlank()
                ? binary(kind)
                : invocation.executor().binary();
        String model = blankToNull(invocation.executor().model());
        String effort = blankToNull(invocation.executor().effort());
        String resume = blankToNull(invocation.resumeSessionId());
        String prompt = pointerPrompt(invocation.invocationId());
        List<String> extra = invocation.executor().args();
        List<String> argv = new ArrayList<>();
        argv.add(binary);
        switch (kind) {
            case OPENCODE -> {
                argv.addAll(List.of("run", "--format", "json", "--thinking"));
                optional(argv, "-m", model);
                optional(argv, "-s", resume);
                optional(argv, "--variant", effort);
                argv.addAll(extra);
                argv.add(prompt);
            }
            case CURSOR -> {
                argv.addAll(List.of(
                        "-p",
                        "--output-format",
                        "stream-json",
                        "--stream-partial-output",
                        "--trust",
                        "--force",
                        "--approve-mcps"));
                optional(argv, "--model", model);
                optional(argv, "--resume", resume);
                argv.addAll(extra);
                argv.add(prompt);
            }
            case ANTIGRAVITY -> {
                argv.addAll(List.of(
                        "--output-format",
                        "stream-json",
                        "--dangerously-skip-permissions",
                        "--disable-slash-commands"));
                optional(argv, "--model", model);
                optional(argv, "--effort", effort);
                optional(argv, "--conversation", resume);
                argv.addAll(extra);
                argv.addAll(List.of("-p", prompt));
            }
            case KIMI -> {
                argv.addAll(List.of("--output-format", "stream-json"));
                optional(argv, "-S", resume);
                argv.addAll(extra);
                argv.addAll(List.of("-p", prompt));
            }
            case PI -> {
                argv.addAll(List.of(
                        "--mode",
                        "json",
                        "--session-id",
                        resume == null ? UUID.randomUUID().toString() : resume));
                optional(argv, "--model", model);
                optional(argv, "--thinking", effort);
                argv.addAll(extra);
                argv.add(prompt);
            }
            case DEEPSEEK -> {
                argv.addAll(List.of("--profile", "headless"));
                if (dshPatch != null) {
                    argv.addAll(List.of("--patch", dshPatch.toString()));
                }
                argv.addAll(List.of("--json", "--permission-mode", "danger-full-access", "--approval-policy", "never"));
                optional(argv, "--session-id", resume);
                argv.addAll(extra);
                argv.add(prompt);
            }
            case ZCODE -> {
                argv.addAll(List.of(
                        "--prompt",
                        prompt,
                        "--output-format",
                        "stream-json",
                        "--mode",
                        "yolo",
                        "--no-color",
                        "--disallowed-tools",
                        ZCODE_DISALLOWED));
                optional(argv, "--resume", resume);
                argv.addAll(extra);
            }
            default -> throw new IllegalStateException(kind);
        }
        return argv;
    }

    @Override
    protected Map<String, String> environment(AgentInvocation invocation, Sandbox sandbox) {
        Path home = sandbox.privateHome(kind);
        if (DEEPSEEK.equals(kind) && home != null) {
            return Map.of("DSH_HOME", home.resolve(".dsh").toString());
        }
        return Map.of();
    }

    @Override
    protected void prepare(AgentInvocation invocation, Sandbox sandbox, EventSink sink) throws IOException {
        CompiledAgent compiled = invocation.compiled();
        if (compiled == null) {
            return;
        }
        if (!compiled.hooks().isEmpty()) {
            sink.emit(Telemetry.of(
                            "runtime",
                            "agent.hooks.unsupported",
                            "progress",
                            kind + " 不支持角色 Hooks，已跳过 " + compiled.hooks().size() + " 个")
                    .withLevel("warn"));
        }
        Path home = sandbox.privateHome(kind);
        if (home == null) {
            sink.emit(Telemetry.of(
                            "runtime",
                            "agent.mcp.skipped",
                            "progress",
                            kind + " 没有私有 HOME，不写入 MCP 配置（开启 loopforge.sandbox.private-home）")
                    .withLevel("warn"));
            return;
        }
        Path file = home.resolve(MCP_PATHS.get(kind));
        AgentMaterializer.writeControlFile(
                sandbox.workspace(), file, Json.write(mcpDocument(kind, compiled.mcpServers())));
        if (CURSOR.equals(kind)) {
            AgentMaterializer.writeControlFile(
                    sandbox.workspace(),
                    home.resolve(".cursor/cli-config.json"),
                    Json.write(Map.of("version", 1, "permissions", Map.of("allow", List.of(), "deny", List.of()))));
        }
    }

    /** The CLI's MCP configuration document (upstream {@code WriteAgentCLIMCPConfig}). */
    static Object mcpDocument(String kind, List<McpServerDefinition> servers) {
        Map<String, Object> entries = AgentMaterializer.mcpEntries(servers);
        Map<String, Object> rendered = new LinkedHashMap<>();
        List<Object> dshInserts = new ArrayList<>();
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(entries).entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            String id = e.getKey();
            String url = (String) entry.get("url");
            switch (kind) {
                case OPENCODE -> rendered.put(id, openCode(entry));
                case ZCODE -> rendered.put(id, zcode(entry));
                case DEEPSEEK -> dshInserts.add(deepSeek(id, entry));
                default -> {
                    Map<String, Object> server = new LinkedHashMap<>();
                    if (url != null) {
                        server.put(ANTIGRAVITY.equals(kind) ? "serverUrl" : "url", url);
                        if (entry.containsKey("headers")) {
                            server.put("headers", entry.get("headers"));
                        }
                    } else {
                        server.put("command", entry.get("command"));
                        for (String key : List.of("args", "env", "cwd")) {
                            boolean noCwd = key.equals("cwd") && (CURSOR.equals(kind) || ANTIGRAVITY.equals(kind));
                            if (entry.containsKey(key) && !noCwd) {
                                server.put(key, entry.get(key));
                            }
                        }
                    }
                    if (KIMI.equals(kind)) {
                        // Kimi gives up on an MCP call after 60 s by default
                        server.put("toolTimeoutMs", 600_000);
                    }
                    rendered.put(id, server);
                }
            }
        }
        return switch (kind) {
            case OPENCODE -> {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("$schema", "https://opencode.ai/config.json");
                doc.put("mcp", rendered);
                doc.put(
                        "permission",
                        Map.of(
                                "edit",
                                "allow",
                                "bash",
                                "allow",
                                "webfetch",
                                "allow",
                                "external_directory",
                                "allow",
                                "doom_loop",
                                "allow"));
                doc.put("autoupdate", false);
                doc.put("share", "disabled");
                yield doc;
            }
            case DEEPSEEK -> dshInserts.isEmpty() ? List.of() : List.of(Map.of("insert", dshInserts));
            case ZCODE -> Map.of("features", Map.of("mcp", true), "mcp", Map.of("servers", rendered));
            default -> Map.of("mcpServers", rendered);
        };
    }

    private static Map<String, Object> openCode(Map<String, Object> entry) {
        Map<String, Object> server = new LinkedHashMap<>();
        if (entry.get("url") != null) {
            server.put("type", "remote");
            server.put("url", entry.get("url"));
            server.put("enabled", true);
            server.put("timeout", 600_000);
            if (entry.containsKey("headers")) {
                server.put("headers", entry.get("headers"));
            }
            return server;
        }
        List<Object> command = new ArrayList<>();
        command.add(entry.get("command"));
        if (entry.get("args") instanceof List<?> args) {
            command.addAll(args);
        }
        server.put("type", "local");
        server.put("command", command);
        server.put("enabled", true);
        server.put("timeout", 600_000);
        if (entry.containsKey("env")) {
            server.put("environment", entry.get("env"));
        }
        return server;
    }

    private static Map<String, Object> zcode(Map<String, Object> entry) {
        Map<String, Object> server = new LinkedHashMap<>();
        if (entry.get("url") != null) {
            server.put("type", "http");
            server.put("url", entry.get("url"));
            if (entry.containsKey("headers")) {
                server.put("headers", entry.get("headers"));
            }
            return server;
        }
        server.put("type", "stdio");
        server.put("command", entry.get("command"));
        server.put("timeoutMs", 600_000);
        server.put("enabled", true);
        for (String key : List.of("args", "cwd", "env")) {
            Object value = entry.get(key);
            if (value != null && !(value instanceof List<?> l && l.isEmpty())) {
                server.put(key, value);
            }
        }
        return server;
    }

    private static Map<String, Object> deepSeek(String id, Map<String, Object> entry) {
        String name = id.replaceAll("[^A-Za-z0-9_-]", "_");
        if (name.length() > 32) {
            name = name.substring(0, 32);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("serverName", name);
        config.put("toolCallTimeoutMs", 600_000);
        if (entry.get("url") != null) {
            config.put("transport", "streamable-http");
            config.put("url", entry.get("url"));
            if (entry.containsKey("headers")) {
                config.put("headers", entry.get("headers"));
            }
        } else {
            config.put("transport", "stdio");
            config.put("command", entry.get("command"));
            for (String key : List.of("args", "env", "cwd")) {
                if (entry.containsKey(key)) {
                    config.put(key, entry.get(key));
                }
            }
        }
        return Map.of("id", "agentflow-mcp-" + name, "name", "@deepseek-ai/dsh-mcp-client", "config", config);
    }

    @Override
    protected StreamParser parser(EventSink sink) {
        return new TranslatingParser(AgentCliTranslators.forKind(kind), new AnthropicStreamParser(kind, sink));
    }

    private static void optional(List<String> argv, String flag, String value) {
        if (value != null) {
            argv.add(flag);
            argv.add(value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "default".equals(value) ? null : value.strip();
    }

    /** Feeds translated lines into the Claude stream parser. */
    static final class TranslatingParser implements StreamParser {
        private final Function<String, List<String>> translator;
        private final AnthropicStreamParser delegate;

        TranslatingParser(Function<String, List<String>> translator, AnthropicStreamParser delegate) {
            this.translator = translator;
            this.delegate = delegate;
        }

        @Override
        public void accept(String line) {
            for (String translated : translator.apply(line)) {
                delegate.accept(translated);
            }
        }

        @Override
        public AgentResult result(int exitCode) {
            return delegate.result(exitCode);
        }
    }
}
