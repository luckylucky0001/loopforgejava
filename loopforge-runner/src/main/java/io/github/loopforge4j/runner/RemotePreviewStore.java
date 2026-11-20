package io.github.loopforge4j.runner;

import io.github.loopforge4j.core.domain.PreviewSettings;
import io.github.loopforge4j.core.preview.PreviewArchive;
import io.github.loopforge4j.core.store.PreviewStore;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Previews built on this machine go to the server (upstream runner preview upload): the site is
 * zipped and uploaded; the server checks it again before it serves anything.
 */
final class RemotePreviewStore implements PreviewStore {

    private final RunnerClient client;

    RemotePreviewStore(RunnerClient client) {
        this.client = client;
    }

    @Override
    public boolean save(
            String runId, String nodeId, PreviewSettings settings, PreviewArchive.Site site, Duration retention)
            throws IOException {
        Path zip = Files.createTempFile("lf-preview-", ".zip");
        try {
            try (OutputStream out = Files.newOutputStream(zip);
                    ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                for (String file : site.files()) {
                    zos.putNextEntry(new ZipEntry(file));
                    Files.copy(site.root().resolve(file), zos);
                    zos.closeEntry();
                }
            }
            String query = "?path=" + encode(settings.path()) + "&index=" + encode(settings.index()) + "&spa="
                    + settings.spa() + "&retentionSec=" + retention.toSeconds()
                    + (nodeId == null ? "" : "&nodeId=" + encode(nodeId));
            client.put("/runs/" + encode(runId) + "/preview" + query, zip, "application/zip");
            return true;
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    @Override
    public void skip(String runId, String nodeId, PreviewSettings settings, String reason) {
        state(runId, SKIPPED, nodeId, settings, reason);
    }

    @Override
    public void fail(String runId, String nodeId, PreviewSettings settings, String reason) {
        state(runId, FAILED, nodeId, settings, reason);
    }

    @Override
    public Optional<String> status(String runId) {
        return client.find("/runs/" + encode(runId) + "/preview")
                .map(n -> n.path("status").asText(null));
    }

    private void state(String runId, String status, String nodeId, PreviewSettings settings, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("nodeId", nodeId);
        body.put("path", settings.path());
        body.put("index", settings.index());
        body.put("spa", settings.spa());
        body.put("reason", reason);
        client.post("/runs/" + encode(runId) + "/preview/state", body);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
