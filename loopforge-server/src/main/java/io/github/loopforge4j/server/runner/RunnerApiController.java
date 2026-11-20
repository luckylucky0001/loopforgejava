package io.github.loopforge4j.server.runner;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.CatalogAsset;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.mcp.PlatformMcp;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import io.github.loopforge4j.server.mcp.ServerPlatformMcp;
import io.github.loopforge4j.server.persistence.JdbcRunStore;
import io.github.loopforge4j.server.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * What an execution machine calls (upstream {@code /runners/*}): the store of its own runs, read
 * access to the catalog its runs use, and platform MCP bindings. Every request is authenticated by
 * the machine's token; a machine never sees or changes another machine's or the server's runs.
 * Bodies use the engine's own JSON mapping so runs round-trip exactly.
 */
@RestController
@RequestMapping("/api/v1/runner-api")
public class RunnerApiController {

    public static final String PATH = "/api/v1/runner-api";
    private static final int MAX_EVENTS = 2_000;

    private final RunnerService runners;
    private final JdbcRunStore runs;
    private final CatalogStore catalog;
    private final ObjectProvider<RunEngine> engines;
    private final ServerPlatformMcp platform;
    private final io.github.loopforge4j.core.delivery.DeliveryProvider delivery;
    private final String publicUrl;

    private final io.github.loopforge4j.server.preview.JdbcPreviewStore previews;
    private final io.github.loopforge4j.core.preview.PreviewArchive.Limits previewLimits;
    private final java.time.Duration previewRetention;

    public RunnerApiController(
            io.github.loopforge4j.server.preview.JdbcPreviewStore previews,
            RunnerService runners,
            JdbcRunStore runs,
            CatalogStore catalog,
            ObjectProvider<RunEngine> engines,
            ServerPlatformMcp platform,
            io.github.loopforge4j.core.delivery.DeliveryProvider delivery,
            LoopForgeProperties properties) {
        this.delivery = delivery;
        this.runners = runners;
        this.runs = runs;
        this.catalog = catalog;
        this.engines = engines;
        this.platform = platform;
        this.publicUrl = properties.publicUrl();
        this.previews = previews;
        this.previewLimits = new io.github.loopforge4j.core.preview.PreviewArchive.Limits(
                properties.preview().maxFiles(),
                properties.preview().maxFileSize().toBytes(),
                properties.preview().maxSize().toBytes());
        this.previewRetention = properties.preview().retention();
    }

    // ------------------------------------------------------------------ machine

    @PostMapping(path = "/heartbeat", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> heartbeat(@RequestBody String body, HttpServletRequest request) {
        Runner runner = runner(request);
        JsonNode in = parse(body);
        List<String> executors = new ArrayList<>();
        in.path("executors").forEach(e -> {
            if (executors.size() < 50) {
                executors.add(clip(e.asString(""), 64));
            }
        });
        runners.heartbeat(
                runner,
                new RunnerStore.Status(
                        clip(in.path("version").asString(""), 64),
                        clip(in.path("host").asString(""), 200),
                        executors,
                        Math.max(0, Math.min(in.path("capacity").asInt(0), 1000))));
        return json(Map.of("runnerId", runner.id(), "name", runner.name()));
    }

    // ------------------------------------------------------------------ runs

    @GetMapping(path = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> list(
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "200") int limit,
            HttpServletRequest request) {
        Runner runner = runner(request);
        Set<RunStatus> statuses = new LinkedHashSet<>();
        if (status != null) {
            status.forEach(s -> statuses.add(RunStatus.fromWire(s)));
        }
        return json(runs.listForRunner(runner.id(), statuses, limit));
    }

    @GetMapping(path = "/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> get(@PathVariable String runId, HttpServletRequest request) {
        return json(owned(runId, runner(request)));
    }

    /** A child SOP run the machine starts for one of its runs. */
    @PostMapping(path = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> insert(@RequestBody String body, HttpServletRequest request) {
        Runner runner = runner(request);
        JsonNode in = parse(body);
        Run run = Json.mapper().convertValue(in.get("run"), Run.class);
        List<RunEvent> events = events(in.get("events"));
        if (run == null
                || !runner.id().equals(run.context().runnerId())
                || !run.context().childRun()
                || run.id() == null
                || !run.id().matches("run-[A-Za-z0-9-]{1,80}")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "machines only create child runs of their own runs");
        }
        Run parent = owned(run.context().parentRunId(), runner);
        if (!Objects.equals(parent.context().projectId(), run.context().projectId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "a child run stays in its parent's project");
        }
        if (runs.find(run.id()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "run exists");
        }
        runs.insert(run, events);
        Run stored = runs.find(run.id()).orElseThrow();
        engine().externalChange(stored, List.of());
        return json(stored);
    }

    /** The machine's {@code RunStore.update}: compare-and-set on the revision it read. */
    @PostMapping(path = "/runs/{runId}/commit", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> commit(@PathVariable String runId, @RequestBody String body, HttpServletRequest request) {
        Runner runner = runner(request);
        Run stored = owned(runId, runner);
        JsonNode in = parse(body);
        long expected = in.path("expectedRevision").asLong(-1);
        Run run = Json.mapper().convertValue(in.get("run"), Run.class);
        List<RunEvent> events = events(in.get("events"));
        List<Artifact> artifacts = in.has("artifacts") && in.get("artifacts").isArray()
                ? Json.mapper().convertValue(in.get("artifacts"), new TypeReference<List<Artifact>>() {})
                : List.of();
        if (run == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "run is required");
        }
        // what defines the run is not the machine's to change: identity, assignment, project, the SOP
        if (!runId.equals(run.id())
                || !Objects.equals(stored.workflowId(), run.workflowId())
                || !Objects.equals(stored.context(), run.context())
                || !Objects.equals(stored.createdAt(), run.createdAt())
                || !Json.write(stored.workflow()).equals(Json.write(run.workflow()))
                || !repositoryIdentity(stored).equals(repositoryIdentity(run))) {
            // the server queries pull requests of these repositories with its own tokens
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "the run's identity, context, SOP snapshot and repositories are fixed");
        }
        if (artifacts.size() > 100) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "too many artifacts");
        }
        for (Artifact a : artifacts) {
            if (!runId.equals(a.runId()) || a.content() != null && a.content().length() > 4 * Artifact.MAX_BYTES) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "invalid artifact");
            }
        }
        JdbcRunStore.Commit result = runs.commit(runId, expected, run, events, artifacts);
        if (!result.committed()) {
            return json(Map.of("committed", false));
        }
        Run now = runs.find(runId).orElseThrow();
        engine().externalChange(now, result.events());
        return json(Map.of("committed", true, "run", now));
    }

    /** What a machine may not change about the run's repositories (it only records the checkout). */
    private static String repositoryIdentity(Run run) {
        return Json.write(run.repositories().stream()
                .map(rc -> java.util.Arrays.asList(
                        rc.repositoryId(),
                        rc.name(),
                        String.valueOf(rc.provider()),
                        rc.cloneUrl(),
                        rc.githubRepo(),
                        rc.baseBranch(),
                        rc.workBranch(),
                        rc.workspaceDir(),
                        String.valueOf(rc.readOnly())))
                .toList());
    }

    @PostMapping(path = "/runs/{runId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> append(@PathVariable String runId, @RequestBody String body, HttpServletRequest request) {
        owned(runId, runner(request));
        List<RunEvent> stored = runs.appendEvents(runId, events(parse(body).get("events")));
        engine().externalEvents(runId, stored);
        return json(stored);
    }

    @GetMapping(path = "/runs/{runId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> events(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "500") int limit,
            HttpServletRequest request) {
        owned(runId, runner(request));
        return json(runs.events(runId, after, Math.max(1, Math.min(limit, 5000))));
    }

    @GetMapping(path = "/runs/{runId}/artifacts", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> artifacts(@PathVariable String runId, HttpServletRequest request) {
        owned(runId, runner(request));
        return json(runs.artifacts(runId));
    }

    @GetMapping(path = "/runs/{runId}/artifacts/{artifactId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> artifact(
            @PathVariable String runId, @PathVariable String artifactId, HttpServletRequest request) {
        owned(runId, runner(request));
        return json(runs.artifact(runId, artifactId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "artifact not found")));
    }

    // ------------------------------------------------------------------ previews

    /** The state of the run's preview (the machine skips archiving what is already there). */
    @GetMapping(path = "/runs/{runId}/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> previewStatus(@PathVariable String runId, HttpServletRequest request) {
        owned(runId, runner(request));
        return json(java.util.Map.of(
                "status",
                previews.status(runId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "preview not found"))));
    }

    /** A preview the machine skipped or failed to archive. */
    @PostMapping(path = "/runs/{runId}/preview/state", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> previewState(
            @PathVariable String runId, @RequestBody String body, HttpServletRequest request) {
        owned(runId, runner(request));
        JsonNode in = parse(body);
        io.github.loopforge4j.core.domain.PreviewSettings settings = previewSettings(
                in.path("path").asText(null),
                in.path("index").asText(null),
                in.path("spa").asBoolean(false));
        String nodeId = in.hasNonNull("nodeId") ? clip(in.get("nodeId").asText(), 128) : null;
        String reason = clip(in.path("reason").asText(""), 1000);
        switch (in.path("status").asText("")) {
            case io.github.loopforge4j.core.store.PreviewStore.SKIPPED ->
                previews.skip(runId, nodeId, settings, reason);
            case io.github.loopforge4j.core.store.PreviewStore.FAILED -> previews.fail(runId, nodeId, settings, reason);
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "status must be skipped or failed");
        }
        return json(java.util.Map.of("status", in.path("status").asText()));
    }

    /** The zipped site the machine built: unpacked, checked and stored like a local archive. */
    @PutMapping(path = "/runs/{runId}/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> previewUpload(
            @PathVariable String runId,
            @RequestParam(required = false) String nodeId,
            @RequestParam String path,
            @RequestParam String index,
            @RequestParam(defaultValue = "false") boolean spa,
            @RequestParam(defaultValue = "0") long retentionSec,
            HttpServletRequest request)
            throws java.io.IOException {
        owned(runId, runner(request));
        io.github.loopforge4j.core.domain.PreviewSettings settings = previewSettings(path, index, spa);
        String node = nodeId == null ? null : clip(nodeId, 128);
        java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("lf-preview-upload");
        try {
            io.github.loopforge4j.server.preview.PreviewUploadsAccess.extract(
                    request.getInputStream(), temp.resolve("site"), previewLimits);
            io.github.loopforge4j.core.preview.PreviewArchive.Site site =
                    io.github.loopforge4j.core.preview.PreviewArchive.collect(
                            temp,
                            new io.github.loopforge4j.core.domain.PreviewSettings("site", settings.index(), spa),
                            previewLimits);
            long seconds = retentionSec <= 0
                            || retentionSec > io.github.loopforge4j.core.domain.PreviewContract.MAX_RETENTION_SEC
                    ? Math.min(
                            previewRetention.toSeconds(),
                            io.github.loopforge4j.core.domain.PreviewContract.MAX_RETENTION_SEC)
                    : retentionSec;
            previews.save(runId, node, settings, site, java.time.Duration.ofSeconds(seconds));
            return json(java.util.Map.of(
                    "status",
                    io.github.loopforge4j.core.store.PreviewStore.READY,
                    "files",
                    site.files().size()));
        } catch (io.github.loopforge4j.core.preview.PreviewArchive.Failure e) {
            previews.fail(runId, node, settings, e.getMessage());
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        } catch (java.io.IOException e) {
            if (io.github.loopforge4j.server.preview.PreviewUploadsAccess.rejected(e)) {
                previews.fail(runId, node, settings, e.getMessage());
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
            }
            throw e;
        } finally {
            io.github.loopforge4j.server.preview.PreviewUploadsAccess.delete(temp);
        }
    }

    private static io.github.loopforge4j.core.domain.PreviewSettings previewSettings(
            String path, String index, boolean spa) {
        String p = io.github.loopforge4j.core.preview.PreviewArchive.safeRelative(path);
        String i = io.github.loopforge4j.core.preview.PreviewArchive.safeRelative(index);
        if (p == null || i == null || p.length() > 1000 || i.length() > 1000) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "preview path and index must be relative paths");
        }
        return new io.github.loopforge4j.core.domain.PreviewSettings(p, i, spa);
    }

    /** Platform and knowledge MCP servers for an agent on the machine, addressed at the server's URL. */
    @PostMapping(path = "/runs/{runId}/platform-mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> platformMcp(
            @PathVariable String runId, @RequestBody String body, HttpServletRequest request) {
        Run run = owned(runId, runner(request));
        JsonNode in = parse(body);
        PlatformMcp.Invocation invocation = new PlatformMcp.Invocation(
                run.id(),
                run.context().projectId(),
                clip(in.path("nodeId").asString(""), 200),
                clip(in.path("agentId").asString(""), 200),
                run.context().triggeredBy(),
                run.workflow().sandboxProfileId(),
                in.path("platform").asBoolean(false),
                in.path("knowledge").asBoolean(false));
        return json(platform.bindAt(invocation, base(request)));
    }

    /**
     * Pull requests of a repository one of the machine's active runs works on, looked up with the
     * server's API credentials (the machine pushes with its own Git credentials; the merge request
     * gate asks the server). The repository as the run froze it is used, not what the machine sends.
     */
    @PostMapping(path = "/deliveries", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> deliveries(@RequestBody String body, HttpServletRequest request) {
        Runner runner = runner(request);
        JsonNode in = parse(body);
        String repositoryId = in.path("repositoryId").asString("");
        io.github.loopforge4j.core.domain.RepositoryContext repository =
                runs.listForRunner(runner.id(), Set.of(RunStatus.RUNNING, RunStatus.WAITING), 500).stream()
                        .flatMap(r -> r.repositories().stream())
                        .filter(r -> r.repositoryId().equals(repositoryId))
                        .findFirst()
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.NOT_FOUND, "repository " + repositoryId + " is not part of an active run"));
        if (!delivery.supports(repository)) {
            return json(Map.of("supported", false));
        }
        try {
            return json(Map.of(
                    "supported",
                    true,
                    "pullRequests",
                    delivery.pullRequests(
                            repository,
                            in.path("head").asString(""),
                            in.path("base").asString(""))));
        } catch (io.github.loopforge4j.core.delivery.DeliveryException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ catalog (read-only)

    @GetMapping(path = "/catalog/{type}/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> catalogItem(@PathVariable String type, @PathVariable String id, HttpServletRequest request) {
        Runner runner = runner(request);
        Set<String> projects = runs.projectsOfRunner(runner.id());
        Optional<?> item = switch (type) {
            case "workflows" -> catalog.workflow(id).filter(w -> visible(w.projectId(), projects));
            case "agents" -> catalog.agent(id);
            case "repositories" -> catalog.repository(id).filter(r -> visible(r.projectId(), projects));
            default -> catalog.asset(kind(type), id);
        };
        return json(item.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, type + " " + id + " not found")));
    }

    @GetMapping(path = "/catalog/{type}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> catalogList(@PathVariable String type, HttpServletRequest request) {
        Runner runner = runner(request);
        Set<String> projects = runs.projectsOfRunner(runner.id());
        List<?> items = switch (type) {
            case "workflows" ->
                catalog.workflows().stream()
                        .filter(w -> visible(w.projectId(), projects))
                        .toList();
            case "agents" -> catalog.agents();
            case "repositories" ->
                catalog.repositories().stream()
                        .filter(r -> visible(r.projectId(), projects))
                        .toList();
            default -> catalog.assets(kind(type));
        };
        return json(items);
    }

    // ------------------------------------------------------------------ helpers

    private Runner runner(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).strip()
                : null;
        return runners.authenticate(token)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "a valid runner token is required"));
    }

    /** The run if it is assigned to this machine; anything else does not exist for it. */
    private Run owned(String runId, Runner runner) {
        return runs.find(runId)
                .filter(r -> runner.id().equals(r.context().runnerId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "run not found"));
    }

    private static boolean visible(String projectId, Set<String> projects) {
        return projectId == null || projectId.isBlank() || projects.contains(projectId);
    }

    @SuppressWarnings("unchecked")
    private static AssetKind<? extends CatalogAsset> kind(String type) {
        return switch (type) {
            case "rules" -> AssetKind.RULE;
            case "skills" -> AssetKind.SKILL;
            case "mcp-servers" -> AssetKind.MCP_SERVER;
            default -> throw new ApiException(HttpStatus.NOT_FOUND, "unknown catalog type " + type);
        };
    }

    private static List<RunEvent> events(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        if (node.size() > MAX_EVENTS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "too many events in one request");
        }
        return Json.mapper().convertValue(node, new TypeReference<List<RunEvent>>() {});
    }

    private RunEngine engine() {
        return engines.getObject();
    }

    private String base(HttpServletRequest request) {
        if (publicUrl != null && !publicUrl.isBlank()) {
            return publicUrl.strip().replaceAll("/+$", "");
        }
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = "http".equals(scheme) && port == 80 || "https".equals(scheme) && port == 443;
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port) + request.getContextPath();
    }

    private static JsonNode parse(String body) {
        try {
            return Json.mapper().readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "malformed JSON");
        }
    }

    private static ResponseEntity<String> json(Object value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Json.write(value));
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
