package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.engine.RunStateException;
import io.github.loopforge4j.core.engine.WorkflowValidationException;
import io.github.loopforge4j.core.store.StaleRunException;
import io.github.loopforge4j.core.store.StoreException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.DatabindException;

/** Maps engine exceptions onto RFC 9457 problem details. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RunNotFoundException.class)
    ProblemDetail notFound(RunNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(io.github.loopforge4j.server.account.AccessPolicy.AccessDeniedException.class)
    ProblemDetail forbidden(io.github.loopforge4j.server.account.AccessPolicy.AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    @ExceptionHandler(io.github.loopforge4j.server.account.AccountService.LoginException.class)
    ProblemDetail unauthorized(io.github.loopforge4j.server.account.AccountService.LoginException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", e.getMessage());
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) {
        return problem(e.status(), e.status().getReasonPhrase(), e.getMessage());
    }

    @ExceptionHandler(RunStateException.class)
    ProblemDetail conflict(RunStateException e) {
        return problem(HttpStatus.CONFLICT, "Operation not allowed in the current state", e.getMessage());
    }

    @ExceptionHandler(StaleRunException.class)
    ProblemDetail stale(StaleRunException e) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification", e.getMessage());
    }

    @ExceptionHandler(WorkflowValidationException.class)
    ProblemDetail invalidDefinition(WorkflowValidationException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid definition", e.getMessage());
        problem.setProperty("problems", e.problems());
        return problem;
    }

    /** Messages of our own argument checks are meant for clients; library messages are not. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", ownMessage(e).orElse("invalid argument"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        log.warn("rejected write: {}", e.getMostSpecificCause().toString());
        return problem(HttpStatus.CONFLICT, "Conflicting or invalid data", "the request conflicts with stored data");
    }

    @ExceptionHandler(StoreException.class)
    ProblemDetail store(StoreException e) {
        log.error("store failure", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Storage unavailable", e.getMessage());
    }

    /**
     * Says where the body could not be parsed (as a JSON path) without echoing parser messages, which
     * name internal Java types.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Throwable cause = ex.getMostSpecificCause();
        String detail = ownMessage(cause).orElse(null);
        if (detail == null) {
            String path = jsonPath(ex);
            detail = path.isEmpty() ? "request body is not valid JSON for this endpoint" : "invalid value at " + path;
        }
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "Malformed request body", detail));
    }

    private static String jsonPath(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof DatabindException databind) {
                StringBuilder path = new StringBuilder();
                for (var reference : databind.getPath()) {
                    if (reference.getPropertyName() != null) {
                        path.append(path.isEmpty() ? "" : ".").append(reference.getPropertyName());
                    } else if (reference.getIndex() >= 0) {
                        path.append('[').append(reference.getIndex()).append(']');
                    }
                }
                return path.toString();
            }
        }
        return "";
    }

    private static java.util.Optional<String> ownMessage(Throwable error) {
        if (!(error instanceof IllegalArgumentException) || error.getMessage() == null) {
            return java.util.Optional.empty();
        }
        StackTraceElement[] trace = error.getStackTrace();
        boolean ours = trace.length > 0 && trace[0].getClassName().startsWith("io.github.loopforge4j.");
        String message = error.getMessage();
        return ours
                ? java.util.Optional.of(message.length() > 300 ? message.substring(0, 300) + "…" : message)
                : java.util.Optional.empty();
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
