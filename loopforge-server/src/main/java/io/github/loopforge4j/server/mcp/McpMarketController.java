package io.github.loopforge4j.server.mcp;

import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.McpServerDefinition.Placement;
import io.github.loopforge4j.core.engine.WorkflowValidator;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.account.ProjectRole;
import io.github.loopforge4j.server.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The MCP market and MCP servers of a project or a person (upstream {@code mcp_market.go}): market
 * entries are installed into a project by its maintainers, people add their own HTTP servers.
 */
@Tag(name = "Assets")
@RestController
@RequestMapping("/api/v1")
public class McpMarketController {

    /** A market entry (upstream {@code mcpMarketItem}). */
    public record Item(
            String id,
            String name,
            String description,
            String category,
            List<String> tags,
            String iconUrl,
            String detailUrl,
            String url,
            String transport,
            String authType,
            boolean official,
            List<String> aliases) {
        public Item {
            tags = tags == null ? List.of() : List.copyOf(tags);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    record Catalog(String source, String tag, String fetchedAt, List<Item> items) {}

    static final Catalog CATALOG = load();

    private static Catalog load() {
        try (InputStream in = McpMarketController.class.getResourceAsStream("/mcp-market.json")) {
            if (in == null) {
                throw new IllegalStateException("mcp-market.json is missing");
            }
            return Json.read(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), Catalog.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final CatalogStore catalog;
    private final AccessPolicy access;

    public McpMarketController(CatalogStore catalog, AccessPolicy access) {
        this.catalog = catalog;
        this.access = access;
    }

    static Optional<Item> item(String id) {
        String key = id == null ? "" : id.strip();
        return CATALOG.items().stream()
                .filter(i -> i.id().equals(key) || i.aliases().contains(key))
                .findFirst();
    }

    @Operation(summary = "Search the MCP market (every term must match)")
    @GetMapping("/mcp-market")
    Map<String, Object> market(@RequestParam(defaultValue = "") String q) {
        String[] terms = q.toLowerCase(Locale.ROOT).strip().split("\\s+");
        List<Item> items = CATALOG.items().stream()
                .filter(i -> {
                    String hay = String.join(" ", i.id(), i.name(), i.description(), i.category(), i.url()) + " "
                            + String.join(" ", i.tags()) + " " + String.join(" ", i.aliases());
                    hay = hay.toLowerCase(Locale.ROOT);
                    for (String t : terms) {
                        if (!t.isEmpty() && !hay.contains(t)) {
                            return false;
                        }
                    }
                    return true;
                })
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", CATALOG.source());
        out.put("fetchedAt", CATALOG.fetchedAt());
        out.put("total", CATALOG.items().size());
        out.put("items", items);
        return out;
    }

    record Install(String marketId, String projectId) {}

    @Operation(summary = "Add a market MCP server to a project (maintainers); roles bind it by its market id")
    @PostMapping("/mcp-market/install")
    ResponseEntity<McpServerDefinition> install(@RequestBody Install body, Principal principal) {
        Item item = item(body.marketId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP 市场条目不存在"));
        String projectId = body.projectId() == null ? "" : body.projectId().strip();
        if (projectId.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "市场 MCP 只能添加到项目；个人范围请添加自定义 MCP");
        }
        access.requireProject(principal, projectId, ProjectRole.MAINTAINER);
        boolean exists = catalog.assets(AssetKind.MCP_SERVER).stream()
                .anyMatch(m -> m.placement() != null
                        && Placement.PROJECT.equals(m.placement().scope())
                        && projectId.equals(m.placement().projectId())
                        && item.id().equals(m.placement().marketId()));
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "当前项目已经添加过这个 MCP");
        }
        McpServerDefinition server = new McpServerDefinition(
                installId(item.id(), Placement.PROJECT, projectId),
                item.name(),
                item.description(),
                item.transport(),
                null,
                null,
                item.url(),
                null,
                null,
                null,
                60,
                null,
                new Placement(Placement.PROJECT, projectId, null, item.id(), item.authType()));
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.saveAsset(AssetKind.MCP_SERVER, server));
    }

    @Operation(summary = "MCP servers of a project")
    @GetMapping("/projects/{projectId}/mcp-servers")
    List<McpServerDefinition> projectServers(@PathVariable String projectId, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return scoped(Placement.PROJECT, projectId, null);
    }

    /** A custom HTTP MCP server: name, description, url, headers. */
    record Custom(
            String id, String name, String description, String url, Map<String, String> headers, Integer timeoutSec) {}

    @Operation(summary = "Add a custom HTTP MCP server to a project (maintainers)")
    @PostMapping("/projects/{projectId}/mcp-servers")
    ResponseEntity<McpServerDefinition> addProjectServer(
            @PathVariable String projectId, @RequestBody Custom body, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.MAINTAINER);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(custom(body, new Placement(Placement.PROJECT, projectId, null, null, null), projectId));
    }

    @Operation(summary = "Remove an MCP server of a project (maintainers)")
    @DeleteMapping("/projects/{projectId}/mcp-servers/{id}")
    ResponseEntity<Void> removeProjectServer(
            @PathVariable String projectId, @PathVariable String id, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.MAINTAINER);
        McpServerDefinition server = catalog.asset(AssetKind.MCP_SERVER, id)
                .filter(m -> m.placement() != null
                        && Placement.PROJECT.equals(m.placement().scope())
                        && projectId.equals(m.placement().projectId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP server not found"));
        catalog.deleteAsset(AssetKind.MCP_SERVER, server.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Your own MCP servers (used by runs and conversations you are responsible for)")
    @GetMapping("/me/mcp-servers")
    List<McpServerDefinition> mine(Principal principal) {
        CurrentUser user = access.requireHuman(principal, "use personal MCP servers");
        return scoped(Placement.PERSONAL, null, user.username());
    }

    @Operation(summary = "Add your own HTTP MCP server")
    @PostMapping("/me/mcp-servers")
    ResponseEntity<McpServerDefinition> addMine(@RequestBody Custom body, Principal principal) {
        CurrentUser user = access.requireHuman(principal, "add personal MCP servers");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(custom(
                        body, new Placement(Placement.PERSONAL, null, user.username(), null, null), user.username()));
    }

    @Operation(summary = "Remove one of your MCP servers")
    @DeleteMapping("/me/mcp-servers/{id}")
    ResponseEntity<Void> removeMine(@PathVariable String id, Principal principal) {
        CurrentUser user = access.requireHuman(principal, "remove personal MCP servers");
        McpServerDefinition server = catalog.asset(AssetKind.MCP_SERVER, id)
                .filter(m -> m.placement() != null
                        && Placement.PERSONAL.equals(m.placement().scope())
                        && user.username().equals(m.placement().owner()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP server not found"));
        catalog.deleteAsset(AssetKind.MCP_SERVER, server.id());
        return ResponseEntity.noContent().build();
    }

    private List<McpServerDefinition> scoped(String scope, String projectId, String owner) {
        List<McpServerDefinition> out = new ArrayList<>();
        for (McpServerDefinition m : catalog.assets(AssetKind.MCP_SERVER)) {
            if (m.placement() != null
                    && scope.equals(m.placement().scope())
                    && (projectId == null || projectId.equals(m.placement().projectId()))
                    && (owner == null || owner.equals(m.placement().owner()))) {
                out.add(m);
            }
        }
        return out;
    }

    /** Custom servers are HTTP only: a person never defines a program the platform starts. */
    private McpServerDefinition custom(Custom body, Placement placement, String key) {
        String base = body.id() == null || body.id().isBlank() ? body.name() : body.id();
        if (base == null || base.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "name is required");
        }
        String url = body.url() == null ? "" : body.url().strip();
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "自定义 MCP 只支持 http(s) 地址");
        }
        String id = installId(base, placement.scope(), key);
        if (catalog.asset(AssetKind.MCP_SERVER, id).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "已经添加过同名 MCP");
        }
        McpServerDefinition server = new McpServerDefinition(
                id,
                body.name() == null || body.name().isBlank()
                        ? base
                        : body.name().strip(),
                body.description(),
                McpServerDefinition.HTTP,
                null,
                null,
                url,
                null,
                body.headers(),
                null,
                body.timeoutSec() == null ? 60 : body.timeoutSec(),
                null,
                placement);
        List<String> problems = WorkflowValidator.assetProblems(server);
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, String.join("; ", problems));
        }
        return catalog.saveAsset(AssetKind.MCP_SERVER, server);
    }

    /** Upstream {@code mcpInstallID}: mcp-<market>-<scope>-<owner>, hashed when too long. */
    static String installId(String marketId, String scope, String owner) {
        String id = "mcp-" + clean(marketId) + "-" + clean(scope) + "-" + clean(owner);
        if (id.length() > 64) {
            try {
                byte[] sum = MessageDigest.getInstance("SHA-256")
                        .digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                id = id.substring(0, 31) + "-" + HexFormat.of().formatHex(sum, 0, 16);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return id;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[^a-zA-Z0-9_-]+", "-").replaceAll("^-+|-+$", "");
    }
}
