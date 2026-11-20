package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.engine.RunStateException;
import io.github.loopforge4j.core.engine.WorkflowValidationException;
import io.github.loopforge4j.core.store.StaleRunException;
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

/** Maps engine exceptions onto RFC 9457 problem details. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(RunNotFoundException.class)
    ProblemDetail notFound(RunNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
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
    ProblemDetail invalidWorkflow(WorkflowValidationException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Invalid workflow", e.getMessage());
        problem.setProperty("problems", e.problems());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage());
    }

    /** Tells API clients which part of the body could not be parsed. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Throwable cause = ex.getMostSpecificCause();
        String detail = cause.getMessage() == null ? "malformed request body" : cause.getMessage();
        int newline = detail.indexOf('\n');
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST, "Malformed request body", newline > 0 ? detail.substring(0, newline) : detail);
        return ResponseEntity.badRequest().body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
