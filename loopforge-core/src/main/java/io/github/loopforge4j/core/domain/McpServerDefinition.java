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
        Instant updatedAt,
        Placement placement)
        implements CatalogAsset {

    /**
     * Where a server applies (upstream MCP scope): {@code null} scope is the shared catalog; a
     * {@code project} server serves that project's runs, a {@code personal} one its owner's.
     *
     * @param marketId the market entry it was installed from; roles may bind the market id
     * @param authType {@code github-token}: the run's GitHub token is sent as the bearer
     */
    public record Placement(String scope, String projectId, String owner, String marketId, String authType) {
        public static final String PROJECT = "project";
        public static final String PERSONAL = "personal";
        public static final String GITHUB_TOKEN = "github-token";

        public Placement {
            scope = blank(scope) ? null : scope.strip();
            projectId = blank(projectId) ? null : projectId.strip();
            owner = blank(owner) ? null : owner.strip();
            marketId = blank(marketId) ? null : marketId.strip();
            authType = blank(authType) ? null : authType.strip();
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    /** Shared catalog servers have no placement or no scope. */
    public boolean shared() {
        return placement == null || placement.scope() == null;
    }

    /** Whether a run of {@code projectId} done for {@code responsible} may use this server. */
    public boolean visibleTo(String projectId, String responsible) {
        if (shared()) {
            return true;
        }
        return switch (placement.scope()) {
            case Placement.PROJECT -> projectId != null && projectId.equals(placement.projectId());
            case Placement.PERSONAL -> responsible != null && responsible.equals(placement.owner());
            default -> false;
        };
    }

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
        return new McpServerDefinition(id, id, null, STDIO, command, args, null, env, null, null, 0, null, null);
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
                instant,
                placement);
    }
}
