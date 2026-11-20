package io.github.loopforge4j.server.assistant;

import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Conversations with the platform assistant. */
@RestController
@RequestMapping("/api/v1/assistant/sessions")
public class AssistantController {

    private final AssistantService assistant;
    private final AssistantStreams streams;

    public AssistantController(AssistantService assistant, AssistantStreams streams) {
        this.assistant = assistant;
        this.streams = streams;
    }

    @GetMapping
    List<AssistantSession> list(Principal principal) {
        return assistant.list(principal);
    }

    @PostMapping
    ResponseEntity<AssistantSession> create(@RequestBody AssistantService.NewSession body, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(assistant.create(body, principal));
    }

    @GetMapping("/{id}")
    AssistantSession get(@PathVariable String id, Principal principal) {
        return assistant.get(id, principal);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, Principal principal) {
        assistant.delete(id, principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    List<AssistantMessage> messages(@PathVariable String id, Principal principal) {
        return assistant.messages(id, principal);
    }

    /**
     * @param mode while the assistant is answering: {@code queue} waits for the reply, {@code steer}
     *     stops it and runs this message next; without it the sender's preference decides
     */
    record Say(@Size(max = 20_000) String content, String mode) {}

    @io.swagger.v3.oas.annotations.Operation(
            summary = "Send a message: starts a reply, or waits ({queued}) while the assistant is answering")
    @PostMapping("/{id}/messages")
    ResponseEntity<Object> send(@PathVariable String id, @RequestBody Say body, Principal principal) {
        AssistantService.Sent sent = assistant.send(id, body.content(), body.mode(), principal);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(sent.message() != null ? sent.message() : java.util.Map.of("queued", sent.queued()));
    }

    @GetMapping("/{id}/queue")
    List<AssistantStore.Queued> queue(@PathVariable String id, Principal principal) {
        return assistant.queue(id, principal);
    }

    @DeleteMapping("/{id}/queue/{queuedId}")
    ResponseEntity<Void> unqueue(@PathVariable String id, @PathVariable String queuedId, Principal principal) {
        assistant.unqueue(id, queuedId, principal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Void> read(@PathVariable String id, Principal principal) {
        assistant.markRead(id, principal);
        return ResponseEntity.noContent().build();
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Your conversations with replies you have not seen")
    @GetMapping("/unread")
    java.util.Map<String, Object> unread(Principal principal) {
        return java.util.Map.of("sessionIds", assistant.unread(principal));
    }

    record Preferences(boolean steerOnSend) {}

    @GetMapping("/preferences")
    Preferences preferences(Principal principal) {
        return new Preferences(assistant.steerOnSend(principal));
    }

    @org.springframework.web.bind.annotation.PutMapping("/preferences")
    Preferences savePreferences(@RequestBody Preferences body, Principal principal) {
        assistant.steerOnSend(principal, body.steerOnSend());
        return new Preferences(assistant.steerOnSend(principal));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<Void> cancel(@PathVariable String id, Principal principal) {
        assistant.cancel(id, principal);
        return ResponseEntity.accepted().build();
    }

    @GetMapping(path = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable String id, Principal principal) {
        assistant.get(id, principal);
        return streams.subscribe(id);
    }
}
