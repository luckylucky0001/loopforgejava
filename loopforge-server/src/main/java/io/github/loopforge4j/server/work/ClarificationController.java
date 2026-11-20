package io.github.loopforge4j.server.work;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AI clarification of a requirement (upstream issue chat-clarify). */
@Tag(name = "Requirements")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/requirements/{number}/clarification")
class ClarificationController {

    private final ClarificationService clarifications;

    ClarificationController(ClarificationService clarifications) {
        this.clarifications = clarifications;
    }

    @GetMapping
    Clarification get(@PathVariable String projectId, @PathVariable int number, Principal principal) {
        return clarifications.get(projectId, number, principal);
    }

    record Answer(@Size(max = 20_000) String message) {}

    @Operation(summary = "Start a clarification round, with the answers to the open questions after the first")
    @PostMapping
    ResponseEntity<Clarification> start(
            @PathVariable String projectId,
            @PathVariable int number,
            @RequestBody(required = false) Answer body,
            Principal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(clarifications.start(projectId, number, body == null ? null : body.message(), principal));
    }

    @Operation(summary = "Save an edited conclusion (ready) or draft; a conclusion goes into the description")
    @PutMapping
    Clarification submit(
            @PathVariable String projectId,
            @PathVariable int number,
            @RequestBody ClarificationService.Submission body,
            Principal principal) {
        return clarifications.submit(projectId, number, body, principal);
    }

    @PostMapping("/cancel")
    ResponseEntity<Void> cancel(@PathVariable String projectId, @PathVariable int number, Principal principal) {
        clarifications.cancel(projectId, number, principal);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Start over: forget the dialogue (the description keeps an earlier conclusion)")
    @DeleteMapping
    ResponseEntity<Void> reset(@PathVariable String projectId, @PathVariable int number, Principal principal) {
        clarifications.reset(projectId, number, principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable String projectId, @PathVariable int number, Principal principal) {
        return clarifications.stream(projectId, number, principal);
    }
}
