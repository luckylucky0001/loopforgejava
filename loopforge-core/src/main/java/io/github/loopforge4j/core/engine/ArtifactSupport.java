package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.ArtifactContract;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandbox;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Artifact contract validation, collection and input handoff (port of upstream {@code artifacts.go}). */
final class ArtifactSupport {

    private ArtifactSupport() {}

    /** Agent contracts first; an SOP node may refine a contract with the same name. */
    static List<ArtifactContract> merge(List<ArtifactContract> agentContracts, List<ArtifactContract> nodeContracts) {
        Map<String, ArtifactContract> merged = new LinkedHashMap<>();
        agentContracts.forEach(c -> merged.put(c.mergeKey(), c));
        nodeContracts.forEach(c -> merged.put(c.mergeKey(), c));
        return List.copyOf(merged.values());
    }

    static void validate(Path workspace, List<ArtifactContract> contracts) throws NodeFailure {
        for (ArtifactContract contract : contracts) {
            Optional<byte[]> content = read(workspace, contract);
            if (content.isEmpty() && contract.required()) {
                throw new NodeFailure(
                        FailureKind.ARTIFACT_CONTRACT,
                        "artifact contract: " + contract.name() + " (" + contract.path() + ") was not created");
            }
        }
    }

    /** Snapshots the node outputs; without contracts the visible output becomes {@code result.md}. */
    static List<Artifact> collect(
            Path workspace,
            Run run,
            WorkflowNode node,
            int attempt,
            List<ArtifactContract> contracts,
            String output,
            Instant now)
            throws NodeFailure {
        List<Artifact> out = new ArrayList<>();
        if (contracts.isEmpty()) {
            out.add(create(
                    run.id(),
                    node.id(),
                    attempt,
                    "result",
                    "result.md",
                    ArtifactContract.DEFAULT_MEDIA_TYPE,
                    output == null ? "" : output,
                    now));
            return out;
        }
        for (ArtifactContract contract : contracts) {
            Optional<byte[]> content = read(workspace, contract);
            if (content.isEmpty()) {
                if (contract.required()) {
                    throw new NodeFailure(
                            FailureKind.ARTIFACT_CONTRACT,
                            "artifact contract: " + contract.name() + " (" + contract.path() + ") was not created");
                }
                continue;
            }
            out.add(create(
                    run.id(),
                    node.id(),
                    attempt,
                    contract.name(),
                    Path.of(contract.path()).normalize().toString(),
                    contract.mediaType(),
                    new String(content.get(), StandardCharsets.UTF_8),
                    now));
        }
        return out;
    }

    private static Optional<byte[]> read(Path workspace, ArtifactContract contract) throws NodeFailure {
        if (!WorkflowValidator.safeRelativePath(contract.path())) {
            throw new NodeFailure(
                    FailureKind.CONFIGURATION, "invalid artifact " + contract.name() + " path " + contract.path());
        }
        Path file = workspace.resolve(contract.path()).normalize();
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new NodeFailure(
                        FailureKind.ARTIFACT_CONTRACT,
                        "artifact contract: " + contract.name() + " is not a regular file");
            }
            if (!file.toRealPath().startsWith(workspace.toRealPath())) {
                throw new NodeFailure(
                        FailureKind.ARTIFACT_CONTRACT,
                        "artifact contract: " + contract.name() + " escapes the workspace");
            }
            if (Files.size(file) > Artifact.MAX_BYTES) {
                throw new NodeFailure(
                        FailureKind.ARTIFACT_CONTRACT,
                        "artifact contract: " + contract.name() + " exceeds " + Artifact.MAX_BYTES + " bytes");
            }
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new NodeFailure(
                    FailureKind.ARTIFACT_CONTRACT,
                    "artifact contract: cannot read " + contract.name() + ": " + e.getMessage());
        }
    }

    static Artifact create(
            String runId,
            String nodeId,
            int attempt,
            String name,
            String path,
            String mediaType,
            String content,
            Instant now) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new Artifact(
                UUID.randomUUID().toString(),
                runId,
                nodeId,
                attempt,
                name,
                path,
                mediaType,
                bytes.length,
                sha256(bytes),
                content,
                now);
    }

    /**
     * Writes the newest artifacts of direct upstream nodes and earlier ancestors into the read-only
     * {@code .loopforge/inputs} tree and returns a manifest for the prompt. Bodies are not inlined so
     * large documents do not blow up the context.
     */
    static String materializeInputs(Path workspace, Run run, WorkflowNode node, List<Artifact> artifacts)
            throws IOException {
        Set<String> direct = Set.copyOf(WorkflowGraph.sources(run.workflow(), node.id()));
        Set<String> prior = WorkflowGraph.ancestors(run.workflow(), node.id());
        Map<String, Artifact> latest = new LinkedHashMap<>();
        for (Artifact artifact : artifacts) {
            if (!direct.contains(artifact.nodeId()) && !prior.contains(artifact.nodeId())) {
                continue;
            }
            String key = artifact.nodeId() + "\u0000" + artifact.name() + "\u0000" + artifact.path();
            Artifact previous = latest.get(key);
            if (previous == null
                    || artifact.attempt() > previous.attempt()
                    || artifact.attempt() == previous.attempt()
                            && artifact.createdAt().isAfter(previous.createdAt())) {
                latest.put(key, artifact);
            }
        }
        List<Artifact> selected = new ArrayList<>(latest.values());
        selected.sort(Comparator.comparing(Artifact::nodeId).thenComparing(Artifact::name));
        StringBuilder manifest = new StringBuilder();
        io.github.loopforge4j.core.runtime.ManagedFiles files =
                selected.isEmpty() ? null : new io.github.loopforge4j.core.runtime.ManagedFiles(workspace);
        for (Artifact artifact : selected) {
            String rel = LocalSandbox.CONTROL_DIR + "/inputs/" + segment(artifact.nodeId()) + "/"
                    + segment(artifact.name()) + extension(artifact.mediaType());
            Path target = workspace.resolve(rel);
            // atomic replace in a verified directory: concurrent branches never see partial files, and a
            // planted symlink is replaced instead of followed
            files.write(
                    target,
                    (artifact.content() == null ? "" : artifact.content()).getBytes(StandardCharsets.UTF_8),
                    "r--r--r--");
            manifest.append("## ")
                    .append(artifact.nodeId())
                    .append(" / ")
                    .append(artifact.name())
                    .append("\n\n- path: `")
                    .append(rel)
                    .append("`\n- sha256: `")
                    .append(artifact.sha256())
                    .append("`\n- bytes: ")
                    .append(artifact.size())
                    .append("\n\nRead this file; the body is not inlined in the prompt.\n\n");
        }
        return manifest.toString().strip();
    }

    /**
     * Parallel branches with a common upstream node materialize the same inputs into the shared
     * workspace at the same time, and an earlier write left the file read-only. Writing a temp file and
     * renaming it over the target is atomic and needs only directory permissions, so concurrent
     * writers never see a partial file or a permission error.
     */
    private static void writeReadOnly(Path target, String content) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            makeReadOnly(temp);
            Files.move(
                    temp,
                    target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void makeReadOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        } catch (IOException | UnsupportedOperationException e) {
            file.toFile().setReadOnly();
        }
    }

    static String extension(String mediaType) {
        if (mediaType == null) {
            return ".md";
        }
        return switch (mediaType) {
            case "application/json" -> ".json";
            case "text/plain" -> ".txt";
            case "text/html" -> ".html";
            case "text/x-diff", "text/x-patch" -> ".diff";
            case "application/yaml", "text/yaml" -> ".yaml";
            default -> ".md";
        };
    }

    private static String segment(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.startsWith(".") ? "_" + cleaned : cleaned;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
