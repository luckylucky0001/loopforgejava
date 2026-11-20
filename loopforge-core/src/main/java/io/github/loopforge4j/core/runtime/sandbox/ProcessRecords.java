package io.github.loopforge4j.core.runtime.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable record of the process groups a sandbox started, so a restarted engine can terminate groups
 * that a crashed engine (kill -9, OOM, host reboot of the JVM only) left running. Without this, the
 * recovered node would execute next to its orphaned predecessor in the same workspace.
 *
 * <p>One file per process: {@code <pid>} containing {@code <processStartMillis> <enginePid>
 * <engineStartMillis>}. A group is only killed when its leader is still alive with the recorded
 * start time (guards against PID reuse) and the engine that started it is not this JVM.
 */
public final class ProcessRecords {

    private static final Logger log = LoggerFactory.getLogger(ProcessRecords.class);
    private static final ProcessHandle SELF = ProcessHandle.current();

    private ProcessRecords() {}

    /** Records {@code process} under {@code directory}; the returned action removes the record. */
    static Runnable record(Path directory, Process process) {
        Optional<Instant> started = process.info().startInstant();
        Optional<Instant> engineStarted = SELF.info().startInstant();
        if (started.isEmpty() || engineStarted.isEmpty()) {
            return () -> {};
        }
        Path file = directory.resolve(Long.toString(process.pid()));
        try {
            // the workspace is two levels up (<workspace>/.loopforge/procs)
            new io.github.loopforge4j.core.runtime.ManagedFiles(
                            directory.getParent().getParent())
                    .write(
                            file,
                            started.get().toEpochMilli() + " " + SELF.pid() + " "
                                    + engineStarted.get().toEpochMilli(),
                            false);
        } catch (IOException e) {
            log.warn("cannot record process {}: {}", process.pid(), e.toString());
            return () -> {};
        }
        return () -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.debug("cannot remove process record {}: {}", file, e.toString());
            }
        };
    }

    /**
     * Kills recorded process groups whose engine is gone and removes all stale records.
     *
     * @return number of process groups that were still alive and got killed
     */
    public static int reclaim(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        long selfStart = SELF.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        int killed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String[] fields =
                        Files.readString(file, StandardCharsets.UTF_8).strip().split(" ");
                long pid;
                try {
                    pid = Long.parseLong(file.getFileName().toString());
                } catch (NumberFormatException e) {
                    Files.deleteIfExists(file);
                    continue;
                }
                if (fields.length == 3
                        && Long.parseLong(fields[1]) == SELF.pid()
                        && Long.parseLong(fields[2]) == selfStart) {
                    continue; // started by this engine; it manages the process itself
                }
                long recordedStart = fields.length == 3 ? Long.parseLong(fields[0]) : -1;
                boolean sameProcess = ProcessHandle.of(pid)
                        .filter(ProcessHandle::isAlive)
                        .flatMap(h -> h.info().startInstant())
                        .map(start -> start.toEpochMilli() == recordedStart)
                        .orElse(false);
                if (sameProcess) {
                    ProcessRunner.killGroup(pid);
                    ProcessHandle.of(pid).ifPresent(h -> {
                        h.descendants().forEach(ProcessHandle::destroyForcibly);
                        h.destroyForcibly();
                    });
                    killed++;
                }
                Files.deleteIfExists(file);
            }
        }
        return killed;
    }
}
