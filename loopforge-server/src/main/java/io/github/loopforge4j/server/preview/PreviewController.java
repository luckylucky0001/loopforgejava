package io.github.loopforge4j.server.preview;

import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.preview.PreviewArchive;
import io.github.loopforge4j.core.store.PreviewStore;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.ProjectRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** A run's frontend preview: its state, links that open it, its backend address and the zip. */
@Tag(name = "previews")
@RestController
public class PreviewController {

    /** Where previews are served; the token in the path is the grant (a browser tab has no bearer token). */
    public static final String PATH = "/preview";

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html;charset=utf-8"),
            Map.entry("htm", "text/html;charset=utf-8"),
            Map.entry("js", "text/javascript;charset=utf-8"),
            Map.entry("mjs", "text/javascript;charset=utf-8"),
            Map.entry("cjs", "text/javascript;charset=utf-8"),
            Map.entry("css", "text/css;charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("txt", "text/plain;charset=utf-8"),
            Map.entry("md", "text/plain;charset=utf-8"),
            Map.entry("xml", "application/xml"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("pdf", "application/pdf"));

    private final RunEngine engine;
    private final AccessPolicy access;
    private final JdbcPreviewStore previews;
    private final PreviewService service;
    private final Map<String, Boolean> routed = new ConcurrentHashMap<>();

    public PreviewController(RunEngine engine, AccessPolicy access, JdbcPreviewStore previews, PreviewService service) {
        this.engine = engine;
        this.access = access;
        this.previews = previews;
        this.service = service;
    }

    private Run readable(String runId, Principal principal) {
        Run run = engine.require(runId);
        access.requireReadRun(principal, run);
        return run;
    }

    @ExceptionHandler(PreviewService.PreviewException.class)
    ResponseEntity<Map<String, Object>> rejected(PreviewService.PreviewException e) {
        HttpStatus status = HttpStatus.valueOf(e.status());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of(
                        "type",
                        "about:blank",
                        "title",
                        status.getReasonPhrase(),
                        "status",
                        e.status(),
                        "detail",
                        e.getMessage()));
    }

    static Map<String, Object> view(JdbcPreviewStore.Preview p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", "preview-" + p.runId());
        out.put("runId", p.runId());
        out.put("nodeId", p.nodeId());
        out.put("kind", "static");
        out.put("status", p.status());
        out.put("sourcePath", p.path());
        out.put("entryFile", p.index());
        out.put("spaFallback", p.spa());
        out.put("backendUrl", p.backendUrl());
        out.put("fileCount", p.files());
        out.put("expandedSize", p.bytes());
        out.put("expiresAt", p.expiresAt());
        out.put("lastAccessedAt", p.lastAccessedAt());
        out.put("createdAt", p.createdAt());
        out.put("updatedAt", p.updatedAt());
        if (p.error() != null) {
            out.put("error", p.error());
        }
        out.values().removeIf(java.util.Objects::isNull);
        return out;
    }

    static Map<String, Object> view(PreviewService.Opened opened) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", opened.session().id());
        out.put("previewId", "preview-" + opened.session().runId());
        out.put("url", opened.url());
        if (opened.session().backendUrl() != null) {
            out.put("backendUrl", opened.session().backendUrl());
        }
        out.put("expiresAt", opened.session().expiresAt());
        return out;
    }

    private static String origin() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    @Operation(summary = "The run's frontend preview: state, size, retention and backend address")
    @GetMapping("/api/v1/runs/{runId}/preview")
    Map<String, Object> preview(@PathVariable String runId, Principal principal) {
        readable(runId, principal);
        return view(
                previews.find(runId).orElseThrow(() -> new PreviewService.PreviewException(404, "preview not found")));
    }

    /** Body of the session and backend requests. */
    record BackendBody(String backendUrl) {}

    @Operation(
            summary =
                    "Open a preview link (valid while used, 30 minutes idle by default); backendUrl overrides the stored one")
    @PostMapping("/api/v1/runs/{runId}/preview/session")
    Map<String, Object> session(
            @PathVariable String runId, @RequestBody(required = false) BackendBody body, Principal principal) {
        readable(runId, principal);
        String backend = body == null ? null : body.backendUrl();
        return view(service.open(runId, backend, access.current(principal).username(), origin()));
    }

    @Operation(summary = "Revoke a preview link")
    @DeleteMapping("/api/v1/runs/{runId}/preview/session/{sessionId}")
    ResponseEntity<Void> revoke(@PathVariable String runId, @PathVariable String sessionId, Principal principal) {
        readable(runId, principal);
        if (!service.revoke(runId, sessionId)) {
            throw new PreviewService.PreviewException(404, "preview session not found");
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Store the backend address preview links use by default; returns a fresh link")
    @PutMapping("/api/v1/runs/{runId}/preview/backend")
    Map<String, Object> backend(@PathVariable String runId, @RequestBody BackendBody body, Principal principal) {
        access.requireOperateRun(principal, readable(runId, principal));
        JdbcPreviewStore.Preview updated = service.setBackend(runId, body == null ? null : body.backendUrl());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preview", view(updated));
        if (updated.serveable()) {
            out.put(
                    "session",
                    view(service.open(runId, null, access.current(principal).username(), origin())));
        }
        return out;
    }

    @Operation(summary = "Delete the run's preview and its files")
    @DeleteMapping("/api/v1/runs/{runId}/preview")
    ResponseEntity<Void> delete(@PathVariable String runId, Principal principal) {
        Run run = readable(runId, principal);
        access.requireProject(principal, run.context().projectId(), ProjectRole.MAINTAINER);
        if (!service.delete(runId)) {
            throw new PreviewService.PreviewException(404, "preview not found");
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Download the run's preview site as a zip")
    @GetMapping("/api/v1/runs/{runId}/preview/download")
    ResponseEntity<StreamingResponseBody> download(@PathVariable String runId, Principal principal) {
        readable(runId, principal);
        JdbcPreviewStore.Preview preview =
                previews.find(runId).orElseThrow(() -> new PreviewService.PreviewException(404, "preview not found"));
        if (PreviewStore.EXPIRED.equals(preview.status())) {
            throw new PreviewService.PreviewException(410, "preview expired");
        }
        if (!preview.serveable()) {
            throw new PreviewService.PreviewException(409, "preview is not ready (" + preview.status() + ")");
        }
        StreamingResponseBody body = out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                previews.each(runId, (path, content) -> {
                    zip.putNextEntry(new ZipEntry(path));
                    zip.write(content);
                    zip.closeEntry();
                });
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(
                        "Content-Disposition",
                        "attachment; filename=\"" + runId.replaceAll("[^A-Za-z0-9_-]", "_") + "-preview.zip\"")
                .body(body);
    }

    // ------------------------------------------------------------------ gateway

    @Operation(summary = "Serve a file of a preview through a session link (no bearer token)")
    @GetMapping(PATH + "/{token}/{*path}")
    ResponseEntity<byte[]> serve(@PathVariable String token, @PathVariable String path, HttpServletRequest request) {
        if (!PreviewService.validToken(token)) {
            return plain(HttpStatus.NOT_FOUND, "not found");
        }
        String prefix = PATH + "/" + token;
        if (path.isEmpty()) {
            // relative URLs in the site resolve against the directory
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(prefix + "/"))
                    .build();
        }
        Optional<PreviewService.Grant> found = service.lookup(token);
        if (found.isEmpty()) {
            return plain(HttpStatus.FORBIDDEN, "预览链接无效或已过期，请回到运行页重新打开。");
        }
        PreviewService.Grant grant = found.get();
        service.renew(grant.session());
        JdbcPreviewStore.Preview preview = grant.preview();
        String runId = preview.runId();
        String relative = path.substring(1);
        boolean directory = relative.isEmpty() || relative.endsWith("/");
        if (directory) {
            relative = relative + preview.index();
        }
        if (PreviewArchive.safeRelative(relative) == null) {
            return plain(HttpStatus.NOT_FOUND, "not found");
        }
        Optional<byte[]> content = previews.file(runId, relative);
        if (content.isEmpty() && !directory && previews.exists(runId, relative + "/" + preview.index())) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(prefix + "/" + relative + "/"))
                    .build();
        }
        if (content.isEmpty() && preview.spa() && acceptsHtml(request)) {
            // client-side routes fall back to the entry page
            relative = preview.index();
            content = previews.file(runId, relative);
        }
        if (content.isEmpty()) {
            return plain(HttpStatus.NOT_FOUND, "预览中没有 " + relative);
        }
        String ext = extension(relative);
        byte[] body = content.get();
        boolean opaque = !service.config().separateOrigin();
        String backend = grant.session().backendUrl();
        if (ext.equals("html") || ext.equals("htm")) {
            boolean usesHistory =
                    routed.computeIfAbsent(runId + "@" + preview.createdAt(), k -> previews.usesHistory(runId));
            if (routed.size() > 1000) {
                routed.clear();
            }
            body = PreviewRewriter.html(new String(body, StandardCharsets.UTF_8), prefix, backend, usesHistory, opaque)
                    .getBytes(StandardCharsets.UTF_8);
        } else if (ext.equals("css")) {
            body = PreviewRewriter.css(new String(body, StandardCharsets.UTF_8), prefix)
                    .getBytes(StandardCharsets.UTF_8);
        } else if (ext.equals("js") || ext.equals("mjs") || ext.equals("cjs")) {
            body = PreviewRewriter.js(new String(body, StandardCharsets.UTF_8), prefix)
                    .getBytes(StandardCharsets.UTF_8);
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(TYPES.getOrDefault(ext, "application/octet-stream")))
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "same-origin")
                .header(
                        "Permissions-Policy",
                        "camera=(), microphone=(), geolocation=(), clipboard-read=(), clipboard-write=()")
                .header("Content-Security-Policy", csp(backend, opaque));
        if (opaque) {
            // the site's own module scripts and data are cross-origin requests from an opaque origin
            response.header("Access-Control-Allow-Origin", "*");
        }
        response.cacheControl(
                !ext.equals("html") && !ext.equals("htm") && relative.contains(".")
                        ? CacheControl.maxAge(java.time.Duration.ofDays(365))
                                .cachePublic()
                                .immutable()
                        : CacheControl.noCache());
        return response.body(body);
    }

    /**
     * Scripts always run in a sandbox. Without a separate preview origin it is an opaque origin: the
     * site cannot reach the console's storage or act with its credentials.
     */
    String csp(String backend, boolean opaque) {
        String ancestors = "'self'";
        String extra = service.config().frameAncestors();
        if (extra != null && !extra.isBlank()) {
            ancestors = ancestors + " " + extra.strip().replace("'self'", "").strip();
        }
        String connect = "'self'";
        String origin = PreviewService.backendOrigin(backend);
        if (origin != null) {
            connect = connect + " " + origin;
        }
        return "sandbox allow-scripts allow-forms allow-modals allow-downloads allow-popups"
                + (opaque ? "" : " allow-same-origin") + "; frame-ancestors " + ancestors.strip()
                + "; base-uri 'self'; connect-src " + connect + "; form-action 'self'";
    }

    private static boolean acceptsHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept == null || accept.isBlank() || accept.contains("text/html") || accept.contains("*/*");
    }

    static String extension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static ResponseEntity<byte[]> plain(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header("Content-Security-Policy", "sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .body(message.getBytes(StandardCharsets.UTF_8));
    }
}
