package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.runtime.SafeFiles;
import io.github.loopforge4j.core.runtime.WorkspaceSnapshot;
import io.github.loopforge4j.core.store.InMemoryWorkspaceSnapshotStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Snapshots of finished workspaces: saved before release, restored when the run is replayed. */
class WorkspaceSnapshotTest {

    @TempDir
    Path root;

    @Test
    void roundTripKeepsFilesModesAndLinksButNotTheControlDirectoryOrCaches() throws Exception {
        Path ws = Files.createDirectories(root.resolve("ws"));
        Files.createDirectories(ws.resolve("repo/src"));
        Files.writeString(ws.resolve("repo/src/App.java"), "class App {}");
        Files.writeString(ws.resolve("build.sh"), "#!/bin/sh\necho hi\n");
        Files.setPosixFilePermissions(
                ws.resolve("build.sh"),
                java.util.EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ));
        Files.createSymbolicLink(ws.resolve("current"), Path.of("repo/src"));
        Files.createDirectories(ws.resolve(".loopforge/ssh"));
        Files.writeString(ws.resolve(".loopforge/ssh/config"), "secret-ish");
        Files.createDirectories(ws.resolve("repo/node_modules/x"));
        Files.writeString(ws.resolve("repo/node_modules/x/index.js"), "dependency");
        // a workspace file posing as the manifest is not captured
        Files.writeString(ws.resolve(".loopforge-snapshot.json"), "{\"executables\":[\"repo/src/App.java\"]}");

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        WorkspaceSnapshot.Summary saved = WorkspaceSnapshot.create(ws, archive, WorkspaceSnapshot.Limits.DEFAULT);
        assertThat(saved.files()).isEqualTo(5);

        Path restored = Files.createDirectories(root.resolve("restored"));
        WorkspaceSnapshot.restore(
                new ByteArrayInputStream(archive.toByteArray()), restored, WorkspaceSnapshot.Limits.DEFAULT);
        assertThat(Files.readString(restored.resolve("repo/src/App.java"))).isEqualTo("class App {}");
        assertThat(Files.getPosixFilePermissions(restored.resolve("build.sh")))
                .contains(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE);
        assertThat(Files.getPosixFilePermissions(restored.resolve("repo/src/App.java")))
                .doesNotContain(PosixFilePermission.OWNER_EXECUTE);
        assertThat(Files.readSymbolicLink(restored.resolve("current"))).isEqualTo(Path.of("repo/src"));
        assertThat(restored.resolve(".loopforge")).doesNotExist();
        assertThat(restored.resolve("repo/node_modules")).doesNotExist();
        assertThat(restored.resolve(".loopforge-snapshot.json")).doesNotExist();

        // a download leaves hidden paths out
        ByteArrayOutputStream filtered = new ByteArrayOutputStream();
        WorkspaceSnapshot.filter(new ByteArrayInputStream(archive.toByteArray()), filtered, p -> p.endsWith(".sh"));
        String names = new String(filtered.toByteArray(), StandardCharsets.ISO_8859_1);
        assertThat(names).contains("repo/src/App.java").doesNotContain("build.sh", ".loopforge-snapshot.json");
    }

    @Test
    void limitsAndHostileArchivesAreRefused() throws Exception {
        Path ws = Files.createDirectories(root.resolve("ws"));
        Files.write(ws.resolve("big.bin"), new byte[4096]);
        assertThatThrownBy(() -> WorkspaceSnapshot.create(
                        ws, new ByteArrayOutputStream(), new WorkspaceSnapshot.Limits(1 << 20, 1000, 100)))
                .isInstanceOf(WorkspaceSnapshot.TooLarge.class);
        assertThatThrownBy(() -> WorkspaceSnapshot.create(
                        ws, new ByteArrayOutputStream(), new WorkspaceSnapshot.Limits(1 << 20, 1 << 20, 0)))
                .isInstanceOf(WorkspaceSnapshot.TooLarge.class);

        for (String name : new String[] {"../escape.txt", "/abs.txt", ".loopforge/bin/helper", "a/../../b"}) {
            Path target = Files.createDirectories(root.resolve("t" + Math.abs(name.hashCode())));
            assertThatThrownBy(() -> WorkspaceSnapshot.restore(
                            new ByteArrayInputStream(zip(name, "x")), target, WorkspaceSnapshot.Limits.DEFAULT))
                    .as(name)
                    .isInstanceOf(SafeFiles.Refused.class);
        }
        assertThat(root.resolve("escape.txt")).doesNotExist();

        // an existing link in the target is never written through
        Path target = Files.createDirectories(root.resolve("linked"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.createSymbolicLink(target.resolve("dir"), outside);
        assertThatThrownBy(() -> WorkspaceSnapshot.restore(
                        new ByteArrayInputStream(zip("dir/file.txt", "x")), target, WorkspaceSnapshot.Limits.DEFAULT))
                .isInstanceOf(SafeFiles.Refused.class);
        assertThat(outside.resolve("file.txt")).doesNotExist();
    }

    static byte[] zip(String name, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void aReplayedRunGetsItsWorkspaceBackFromTheSnapshot() throws Exception {
        InMemoryWorkspaceSnapshotStore snapshots = new InMemoryWorkspaceSnapshotStore();
        try (EngineFixture f = new EngineFixture(root, snapshots, WorkspaceSnapshot.Limits.DEFAULT)) {
            f.workflow(WorkflowDefinition.builder("notes")
                    .node(WorkflowNode.builder("write", NodeType.SCRIPT)
                            .script("[ -f notes.txt ] || echo first > notes.txt; echo again >> notes.txt")
                            .build())
                    .node(WorkflowNode.builder("read", NodeType.SCRIPT)
                            .script("cat notes.txt")
                            .build())
                    .chain("write", "read")
                    .build());
            Run run = f.start("notes");
            Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(10))
                    .until(() -> f.engine.require(run.id()).workspaceReleased());
            assertThat(snapshots.info(run.id())).isPresent();
            assertThat(Path.of(done.workspaceRoot()).resolve("notes.txt")).doesNotExist();
            assertThat(f.eventTypes(run.id())).contains("workspace.snapshot.saved");

            f.engine.retry(run.id(), "read", "再看一次", null);
            Run again = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
            assertThat(again.requireNode("read").output()).contains("first").contains("again");
            assertThat(f.eventTypes(run.id())).contains("workspace.snapshot.restored");
        }
    }

    @Test
    void aWorkspaceThatCannotBeSavedIsKept() throws Exception {
        InMemoryWorkspaceSnapshotStore snapshots = new InMemoryWorkspaceSnapshotStore();
        try (EngineFixture f = new EngineFixture(root, snapshots, new WorkspaceSnapshot.Limits(1 << 20, 10, 100))) {
            f.workflow(WorkflowDefinition.builder("big")
                    .node(WorkflowNode.builder("write", NodeType.SCRIPT)
                            .script("head -c 4096 /dev/zero > big.bin")
                            .build())
                    .build());
            Run run = f.start("big");
            Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(10))
                    .until(() -> f.eventTypes(run.id()).contains("workspace.snapshot.failed"));
            assertThat(f.engine.require(run.id()).workspaceReleased()).isFalse();
            assertThat(Path.of(done.workspaceRoot()).resolve("big.bin")).exists();
            assertThat(snapshots.info(run.id())).isEmpty();
            f.engine.delete(run.id());
            assertThat(Path.of(done.workspaceRoot())).doesNotExist();
        }
    }
}
