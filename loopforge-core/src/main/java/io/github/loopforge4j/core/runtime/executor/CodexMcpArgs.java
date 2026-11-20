package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.runtime.materialize.CompiledAgent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders a compiled role as Codex {@code -c} overrides (upstream {@code codexConfigArgs}, {@code
 * codexMCPServerArgs}, {@code codexHookConfigArgs}). Values that may carry secrets never go on argv:
 * stdio environments go through an owner-only launcher script in the invocation's runtime
 * directory, HTTP headers through {@code env_http_headers}.
 */
final class CodexMcpArgs {

    private CodexMcpArgs() {}

    /** Arguments plus the extra process environment they refer to. */
    record Rendered(List<String> args, Map<String, String> environment) {}

    static Map<String, String> headerEnvironment(CompiledAgent compiled) {
        Map<String, String> env = new java.util.LinkedHashMap<>();
        int header = 0;
        for (McpServerDefinition server : compiled.mcpServers()) {
            if (server.http()) {
                for (String value : server.headers().values()) {
                    env.put("LOOPFORGE_MCP_HEADER_" + header++, value);
                }
            }
        }
        return env;
    }

    static Rendered render(CompiledAgent compiled) throws IOException {
        List<String> args = new ArrayList<>();
        Map<String, String> env = new java.util.LinkedHashMap<>();
        if (compiled.hasSkills()) {
            List<String> entries = new ArrayList<>();
            for (CompiledAgent.CompiledSkill skill : compiled.skills()) {
                entries.add("{ path = " + toml(skill.root().toString()) + ", enabled = true }");
            }
            args.add("-c");
            args.add("skills.config=[" + String.join(", ", entries) + "]");
        }
        int header = 0;
        for (McpServerDefinition server : compiled.mcpServers()) {
            String prefix = "mcp_servers." + server.id().replaceAll("[^A-Za-z0-9_-]", "_") + ".";
            if (server.http()) {
                add(args, prefix + "url=" + toml(server.url()));
                if (!server.headers().isEmpty()) {
                    List<String> pairs = new ArrayList<>();
                    for (Map.Entry<String, String> h : server.headers().entrySet()) {
                        String variable = "LOOPFORGE_MCP_HEADER_" + header++;
                        env.put(variable, h.getValue());
                        pairs.add(toml(h.getKey()) + " = " + toml(variable));
                    }
                    add(args, prefix + "env_http_headers={ " + String.join(", ", pairs) + " }");
                }
            } else {
                List<String> argv = new ArrayList<>();
                String command = server.command();
                if (!server.environment().isEmpty()) {
                    Path launcher = launcher(compiled.runtimeDir(), server);
                    command = "sh";
                    argv.add(launcher.toString());
                } else {
                    argv.addAll(server.args());
                }
                add(args, prefix + "command=" + toml(command));
                if (!argv.isEmpty()) {
                    add(
                            args,
                            prefix + "args=["
                                    + String.join(
                                            ",",
                                            argv.stream()
                                                    .map(CodexMcpArgs::toml)
                                                    .toList()) + "]");
                }
                if (server.workingDirectory() != null
                        && !server.workingDirectory().isBlank()) {
                    add(args, prefix + "cwd=" + toml(server.workingDirectory()));
                }
            }
            if (server.timeoutSec() > 0) {
                add(args, prefix + "tool_timeout_sec=" + server.timeoutSec() + ".0");
            }
        }
        if (!compiled.hooks().isEmpty()) {
            Map<String, List<String>> grouped = new java.util.LinkedHashMap<>();
            for (CompiledAgent.CompiledHook hook : compiled.hooks()) {
                String group = "{hooks=[{type=\"command\",command=" + toml(hook.command()) + ",timeout="
                        + hook.timeoutSec() + "}]" + (hook.matcher() == null ? "" : ",matcher=" + toml(hook.matcher()))
                        + "}";
                grouped.computeIfAbsent(hook.event(), e -> new ArrayList<>()).add(group);
            }
            grouped.forEach((event, groups) -> add(args, "hooks." + event + "=[" + String.join(",", groups) + "]"));
        }
        return new Rendered(args, env);
    }

    private static void add(List<String> args, String value) {
        args.add("-c");
        args.add(value);
    }

    private static Path launcher(Path runtimeDir, McpServerDefinition server) throws IOException {
        StringBuilder script = new StringBuilder("#!/bin/sh\nexec env");
        server.environment().forEach((k, v) -> script.append(' ').append(quote(k + "=" + v)));
        script.append(' ').append(quote(server.command()));
        server.args().forEach(a -> script.append(' ').append(quote(a)));
        script.append('\n');
        Path file = runtimeDir.resolve("mcp-" + server.id().replaceAll("[^A-Za-z0-9_-]", "_") + ".sh");
        Files.writeString(file, script, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // non-POSIX file system
        }
        return file;
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** TOML basic string (JSON string syntax is compatible for the characters JSON escapes). */
    static String toml(String value) {
        return io.github.loopforge4j.core.mcp.MiniJson.write(value == null ? "" : value);
    }
}
