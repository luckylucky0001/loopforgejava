package io.github.loopforge4j.server.tracker;

/** A tracker call failed; the message names the host and status, never credentials or bodies. */
public class TrackerException extends Exception {
    public TrackerException(String message) {
        super(message);
    }
}
