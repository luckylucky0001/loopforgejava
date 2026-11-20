package io.github.loopforge4j.server.web;

import org.springframework.http.HttpStatus;

/** A failure with a definite HTTP status and a message safe to show the caller. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
