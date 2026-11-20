package io.github.loopforge4j.core.delivery;

/** The hosting API could not be queried (no credential, network or API error). */
public class DeliveryException extends Exception {

    private static final long serialVersionUID = 1L;

    public DeliveryException(String message) {
        super(message);
    }

    public DeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
