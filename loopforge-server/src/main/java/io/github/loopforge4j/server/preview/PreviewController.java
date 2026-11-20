package io.github.loopforge4j.server.preview;

import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Opening and downloading the static sites preview nodes archive. */
@Tag(name = "previews")
@RestController
public class PreviewController {

    /** Where previews are served; the path carries a signed, expiring grant instead of a bearer token. */
    public static final String PATH = "/preview";

    /**
     * Scripts run, but in an opaque origin: they cannot read the console's storage or call the API
     * with its credentials, and the site cannot be framed by other pages.
     */
    static final String CSP = "sandbox allow-scripts allow-forms allow-popups allow-modals allow-downloads;"
            + " default-src 'self' data: blob: 'unsafe-inline' 'unsafe-eval'; frame-ancestors 'none';"
            + " base-uri 'self'";

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html;charset=utf-8"),
            Map.entry("htm", "text/html;charset=utf-8"),
            Map.entry("js", "text/javascript;charset=utf-8"),
            Map.entry("mjs", "text/javascript;charset=utf-8"),
            Map.entry("css", "text/css;charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
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
    private final PreviewLinks links;

    public PreviewController(RunEngine engine, AccessPolicy access, JdbcPreviewStore previews, PreviewLinks links) {
        this.engine = engine;
        this.access = access;
        this.previews = previews;
        this.links = links;
    }

    private Run readable(String runId, Principal principal) {
        Run run = engine.require(runId);
        access.requireReadRun(principal, run);
        return run;
    }

    @Operation(summary = "The run's archived preview and a signed link to open it ({} when there is none)")
    @GetMapping("/api/v1/runs/{runId}/preview")
    Map<String, Object> preview(@PathVariable String runId, Principal principal) {
        readable(runId, principal);
        Map<String, Object> body = new LinkedHashMap<>();
        previews.find(runId).ifPresent(p -> {
            PreviewLinks.Link link = links.issue(runId);
            body.put("runId", p.runId());
            body.put("nodeId", p.nodeId());
            body.put("path", p.path());
            body.put("index", p.index());
            body.put("spa", p.spa());
            body.put("files", p.files());
            body.put("bytes", p.bytes());
            body.put("createdAt", p.createdAt());
            body.put("url", PATH + "/" + runId + "/" + link.token() + "/");
            body.put("expiresAt", link.expiresAt());
        });
        return body;
    }

    @Operation(summary = "Download the run's preview site as a zip")
    @GetMapping("/api/v1/runs/{runId}/preview/download")
    ResponseEntity<StreamingResponseBody> download(@PathVariable String runId, Principal principal) {
        readable(runId, principal);
        previews.find(runId).orElseThrow(() -> new RunNotFoundException("run '" + runId + "' has no preview"));
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

    @Operation(summary = "Serve a file of a preview through a signed link (no bearer token)")
    @GetMapping(PATH + "/{runId}/{token}/{*path}")
    ResponseEntity<byte[]> serve(@PathVariable String runId, @PathVariable String token, @PathVariable String path) {
        if (!links.valid(runId, token)) {
            return plain(HttpStatus.FORBIDDEN, "预览链接无效或已过期，请回到运行页重新打开。");
        }
        Optional<JdbcPreviewStore.Preview> found = previews.find(runId);
        if (found.isEmpty()) {
            return plain(HttpStatus.NOT_FOUND, "这次运行没有预览。");
        }
        JdbcPreviewStore.Preview preview = found.get();
        String base = PATH + "/" + runId + "/" + token + "/";
        if (path.isEmpty()) {
            // relative URLs in the site resolve against the directory
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(base))
                    .build();
        }
        String relative = path.substring(1);
        if (relative.isEmpty() || relative.endsWith("/")) {
            relative = relative + preview.index();
        }
        Optional<byte[]> content = previews.file(runId, relative);
        if (content.isEmpty() && !relative.endsWith("/" + preview.index())) {
            String directoryIndex = relative + "/" + preview.index();
            if (previews.exists(runId, directoryIndex)) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(base + relative + "/"))
                        .build();
            }
        }
        if (content.isEmpty() && preview.spa() && !lastSegment(relative).contains(".")) {
            // client-side routes fall back to the entry page
            relative = preview.index();
            content = previews.file(runId, relative);
        }
        if (content.isEmpty()) {
            return plain(HttpStatus.NOT_FOUND, "预览中没有 " + relative);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type(relative)))
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header("Content-Security-Policy", CSP)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .body(content.get());
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    static String type(String path) {
        String name = lastSegment(path);
        int dot = name.lastIndexOf('.');
        return dot < 0
                ? "application/octet-stream"
                : TYPES.getOrDefault(name.substring(dot + 1).toLowerCase(Locale.ROOT), "application/octet-stream");
    }

    private static ResponseEntity<byte[]> plain(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header("Content-Security-Policy", "sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .body(message.getBytes(StandardCharsets.UTF_8));
    }
}
