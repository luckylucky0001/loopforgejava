package io.github.loopforge4j.server.ops;

import io.github.loopforge4j.server.account.AccessPolicy;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Administrators look at, update and roll back the managed agent CLIs. */
@RestController
public class ManagedCliController {

    private final ManagedCliService service;
    private final AccessPolicy access;

    public ManagedCliController(ManagedCliService service, AccessPolicy access) {
        this.service = service;
        this.access = access;
    }

    @ExceptionHandler({IOException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, Object>> refused(Exception e) {
        HttpStatus status = e instanceof IllegalArgumentException ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of(
                        "type",
                        "about:blank",
                        "title",
                        status.getReasonPhrase(),
                        "status",
                        status.value(),
                        "detail",
                        e.getMessage() == null ? "failed" : e.getMessage()));
    }

    @Operation(summary = "Managed agent CLIs: installed, previous and rejected versions, last report, next check")
    @GetMapping("/api/v1/admin/managed-clis")
    Map<String, Object> view(Principal principal) throws IOException {
        access.requireAdmin(principal);
        return service.view();
    }

    record UpdateRequest(String provider) {}

    @Operation(summary = "Update the managed CLIs now (all, or one provider); runs in the background")
    @PostMapping("/api/v1/admin/managed-clis/update")
    ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) UpdateRequest body, Principal principal)
            throws IOException {
        access.requireAdmin(principal);
        if (!service.enabled()) {
            throw new IOException("本部署没有托管 CLI（loopforge.managed-cli.enabled）");
        }
        if (!service.startUpdate(body == null ? null : body.provider())) {
            throw new IOException("已有更新在进行");
        }
        return ResponseEntity.accepted().body(Map.of("updating", true));
    }

    @Operation(summary = "Roll a managed CLI back to its previous version (the rejected one is not reinstalled)")
    @PostMapping("/api/v1/admin/managed-clis/{provider}/rollback")
    Map<String, Object> rollback(@PathVariable String provider, Principal principal) throws IOException {
        access.requireAdmin(principal);
        return Map.of("report", service.rollback(provider));
    }
}
