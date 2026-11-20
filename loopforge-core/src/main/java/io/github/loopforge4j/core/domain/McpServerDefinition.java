package io.github.loopforge4j.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An MCP server a role may use (upstream {@code MCPServerDefinition}). The role's list is the only
 * allowlist: repository-local or user-level MCP configuration is never loaded.
 *
 * @param transport {@code stdio} or {@code http}
 * @param command stdio: program to start
 * @param url http: endpoint
 * @param environment stdio: variables; {@code ${env:NAME}} is resolved from the server's
 *     configured secrets at launch, never stored resolved
 * @param headers http: request headers, same placeholder rules
 * @param timeoutSec tool call timeout, 0 for the CLI default
 */
public record McpServerDefinition(
        String id,
        String name,
        String description,
        String transport,
        String command,
        List<String> args,
        String url,
        Map<String, String> environment,
        Map<String, String> headers,
        String workingDirectory,
        int timeoutSec,
        Instant updatedAt)
        implements CatalogAsset {

    public static final String STDIO = "stdio";
    public static final String HTTP = "http";

    public McpServerDefinition {
        id = id == null ? "" : id.strip();
        name = name == null || name.isBlank() ? id : name.strip();
        transport =
                transport == null || transport.isBlank() ? (url != null && !url.isBlank() ? HTTP : STDIO) : transport;
        args = args == null ? List.of() : List.copyOf(args);
        environment =
                environment == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(environment));
        headers = headers == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(headers));
    }

    public boolean http() {
        return HTTP.equals(transport);
    }

    public static McpServerDefinition stdio(String id, String command, List<String> args, Map<String, String> env) {
        return new McpServerDefinition(id, id, null, STDIO, command, args, null, env, null, null, 0, null);
    }

    @Override
    public McpServerDefinition withUpdatedAt(Instant instant) {
        return new McpServerDefinition(
                id,
                name,
                description,
                transport,
                command,
                args,
                url,
                environment,
                headers,
                workingDirectory,
                timeoutSec,
                instant);
    }
}
