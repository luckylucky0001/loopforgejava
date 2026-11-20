package io.github.loopforge4j.server.runner;

import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Managing execution machines from the console. */
@RestController
@RequestMapping("/api/v1/runners")
public class RunnerController {

    private final RunnerService runners;
    private final Clock clock;

    private final String runnerJar;

    public RunnerController(
            RunnerService runners,
            Clock clock,
            @org.springframework.beans.factory.annotation.Value(
                            "${loopforge.runner-jar:/app/runner/loopforge-runner.jar}")
                    String runnerJar) {
        this.runners = runners;
        this.clock = clock;
        this.runnerJar = runnerJar;
    }

    @GetMapping
    List<Map<String, Object>> list(Principal principal) {
        return runners.visible(principal).stream().map(this::view).toList();
    }

    record NewRunner(@Size(max = 200) String name, boolean shared) {}

    @PostMapping
    ResponseEntity<Map<String, Object>> register(@RequestBody NewRunner body, Principal principal) {
        RunnerService.Registered registered = runners.register(body.name(), body.shared(), principal);
        Map<String, Object> out = view(registered.runner());
        out.put("token", registered.token());
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    /**
     * The execution machine program ({@code java -jar}), when this installation ships it
     * ({@code loopforge.runner-jar}, in the container image {@code /app/runner/loopforge-runner.jar}).
     */
    @GetMapping("/agent.jar")
    ResponseEntity<org.springframework.core.io.Resource> agentJar() {
        java.nio.file.Path jar = java.nio.file.Path.of(runnerJar);
        if (!java.nio.file.Files.isRegularFile(jar)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"loopforge-runner.jar\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(new org.springframework.core.io.FileSystemResource(jar));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, Principal principal) {
        runners.delete(id, principal);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> view(Runner r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.id());
        out.put("name", r.name());
        out.put("owner", r.ownerName());
        out.put("shared", r.shared());
        out.put("tokenPrefix", r.tokenPrefix());
        out.put("version", r.version());
        out.put("host", r.host());
        out.put("executors", r.executors());
        out.put("capacity", r.capacity());
        out.put("online", r.online(clock.instant()));
        out.put("createdAt", r.createdAt());
        out.put("lastSeenAt", r.lastSeenAt());
        return out;
    }
}
