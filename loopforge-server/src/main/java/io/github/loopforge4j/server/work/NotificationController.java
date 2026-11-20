package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.engine.WorkflowValidationException;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.ProjectRole;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook channels. {@code projectId} selects a project's channels (maintainers); without it the
 * global channels (administrators), which receive events of every project.
 */
@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationStore store;
    private final AccessPolicy access;
    private final boolean allowPrivate;

    NotificationController(
            NotificationStore store,
            AccessPolicy access,
            io.github.loopforge4j.server.config.LoopForgeProperties properties) {
        this.store = store;
        this.access = access;
        this.allowPrivate = properties.notifications().allowPrivateTargets();
    }

    @GetMapping
    List<NotificationChannel> list(@RequestParam(required = false) String projectId, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.MAINTAINER);
        return store.list(projectId);
    }

    @PostMapping
    ResponseEntity<NotificationChannel> create(@RequestBody NotificationChannel body, Principal principal) {
        access.requireProject(principal, body.projectId(), ProjectRole.MAINTAINER);
        validate(body);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(store.save(
                        copy(body, "ntf_" + UUID.randomUUID().toString().replace("-", ""), body.secret())));
    }

    @PutMapping("/{id}")
    NotificationChannel update(@PathVariable String id, @RequestBody NotificationChannel body, Principal principal) {
        NotificationChannel existing = find(id, principal);
        access.requireProject(principal, body.projectId(), ProjectRole.MAINTAINER);
        validate(body);
        // an omitted secret keeps the stored one
        return store.save(copy(body, existing.id(), body.secret() == null ? existing.secret() : body.secret()));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, Principal principal) {
        find(id, principal);
        store.delete(id);
        return ResponseEntity.noContent().build();
    }

    private NotificationChannel find(String id, Principal principal) {
        NotificationChannel channel = store.find(id)
                .orElseThrow(() -> new RunNotFoundException("notification channel '" + id + "' not found"));
        access.requireProject(principal, channel.projectId(), ProjectRole.MAINTAINER);
        return channel;
    }

    private static NotificationChannel copy(NotificationChannel body, String id, String secret) {
        return new NotificationChannel(
                id,
                body.projectId(),
                body.name().strip(),
                body.enabled(),
                body.kind(),
                body.url().strip(),
                secret,
                body.events(),
                null,
                null,
                null,
                null);
    }

    private void validate(NotificationChannel body) {
        List<String> problems = new ArrayList<>();
        if (body.name() == null || body.name().isBlank() || body.name().length() > 200) {
            problems.add("name is required (at most 200 characters)");
        }
        String url = body.url() == null ? "" : body.url().strip();
        if (url.length() > 2000) {
            problems.add("url is too long");
        } else {
            String problem = WebhookTargets.problem(url, allowPrivate);
            if (problem != null) {
                problems.add(problem);
            }
        }
        if (!NotificationChannel.KINDS.contains(body.kind())) {
            problems.add("kind must be one of " + new java.util.TreeSet<>(NotificationChannel.KINDS));
        }
        for (String event : body.events()) {
            if (!NotificationChannel.EVENTS.contains(event)) {
                problems.add(
                        "unknown event " + event + "; known: " + new java.util.TreeSet<>(NotificationChannel.EVENTS));
            }
        }
        if (body.secret() != null && body.secret().length() > 500) {
            problems.add("secret is too long");
        }
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
    }
}
