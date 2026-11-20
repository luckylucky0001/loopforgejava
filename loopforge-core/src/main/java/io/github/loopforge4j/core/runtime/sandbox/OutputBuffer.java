package io.github.loopforge4j.core.runtime.sandbox;

/** Thread-safe buffer that keeps only the last {@code limit} characters of process output. */
public final class OutputBuffer {

    public static final int DEFAULT_LIMIT = 256 * 1024;

    private final int limit;
    private final StringBuilder buffer = new StringBuilder();
    private boolean truncated;

    public OutputBuffer() {
        this(DEFAULT_LIMIT);
    }

    public OutputBuffer(int limit) {
        this.limit = limit;
    }

    public synchronized void appendLine(String line) {
        buffer.append(line).append('\n');
        int excess = buffer.length() - limit;
        if (excess > 0) {
            buffer.delete(0, excess);
            truncated = true;
        }
    }

    @Override
    public synchronized String toString() {
        return truncated ? "…(truncated)\n" + buffer : buffer.toString();
    }
}
