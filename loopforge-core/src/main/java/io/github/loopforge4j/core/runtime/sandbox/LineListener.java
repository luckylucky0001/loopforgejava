package io.github.loopforge4j.core.runtime.sandbox;

/** Receives each stdout line of a running process (stream protocols are line-delimited JSON). */
@FunctionalInterface
public interface LineListener {

    LineListener NONE = line -> {};

    void onLine(String line);
}
