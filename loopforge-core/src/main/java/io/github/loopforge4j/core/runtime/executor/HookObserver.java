package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.Telemetry;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tails the hook observation log the hook wrappers append to and turns each line into a {@code
 * hook.started} / {@code hook.finished} event (upstream {@code observability.WatchHookFile}).
 */
final class HookObserver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HookObserver.class);
    private static final long POLL_MILLIS = 250;

    private final Path file;
    private final EventSink sink;
    private final Thread thread;
    private volatile boolean stopped;
    private long offset;
    private final StringBuilder partial = new StringBuilder();

    private HookObserver(Path file, EventSink sink) {
        this.file = file;
        this.sink = sink;
        this.thread = Thread.ofVirtual().name("hook-observer").start(this::loop);
    }

    static HookObserver watch(Path file, EventSink sink) {
        return file == null ? null : new HookObserver(file, sink);
    }

    private void loop() {
        while (!stopped) {
            drain();
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private synchronized void drain() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long length = in.length();
            if (length <= offset) {
                return;
            }
            byte[] bytes = new byte[(int) Math.min(length - offset, 1 << 20)];
            in.seek(offset);
            in.readFully(bytes);
            offset += bytes.length;
            partial.append(new String(bytes, StandardCharsets.UTF_8));
            int newline;
            while ((newline = partial.indexOf("\n")) >= 0) {
                String line = partial.substring(0, newline).strip();
                partial.delete(0, newline + 1);
                if (!line.isEmpty()) {
                    emit(line);
                }
            }
        } catch (IOException e) {
            log.debug("cannot read hook log {}: {}", file, e.toString());
        }
    }

    private void emit(String line) {
        Map<String, Object> record;
        try {
            record = Json.mapper()
                    .readValue(
                            line,
                            Json.mapper()
                                    .getTypeFactory()
                                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (RuntimeException e) {
            return;
        }
        String phase = String.valueOf(record.get("phase"));
        boolean finish = "finish".equals(phase);
        Object exit = record.get("exitCode");
        boolean failed = finish && exit instanceof Number n && n.intValue() != 0;
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (String key : new String[] {"hookId", "event", "exitCode", "durationMs", "repository"}) {
            Object value = record.get(key);
            if (value != null && !"".equals(value)) {
                attributes.put(key, value);
            }
        }
        String hook = String.valueOf(record.get("hookId"));
        String message = finish ? "Hook " + hook + " 结束（exit " + exit + "）" : "Hook " + hook + " 开始";
        sink.emit(new Telemetry(
                "hook",
                finish ? "hook.finished" : "hook.started",
                finish ? "finish" : "start",
                failed ? "warn" : "info",
                message,
                null,
                null,
                null,
                attributes));
    }

    @Override
    public void close() {
        stopped = true;
        thread.interrupt();
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drain();
    }
}
