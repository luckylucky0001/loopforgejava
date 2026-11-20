package io.github.loopforge4j.core.store;

/**
 * A persistence adapter failed. The message is deliberately generic (no SQL, no driver text) because
 * it can end up in node errors and API responses; the cause keeps the details for logs.
 */
public class StoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
