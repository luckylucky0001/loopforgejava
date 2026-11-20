package io.github.loopforge4j.core.runtime;

/** Receives telemetry; implementations must be thread-safe and must not block for long. */
@FunctionalInterface
public interface EventSink {

    EventSink NOOP = telemetry -> {};

    void emit(Telemetry telemetry);
}
