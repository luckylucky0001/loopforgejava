package io.github.loopforge4j.server.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's own model keys and Git tokens (upstream personal settings). */
@Tag(name = "Accounts")
@RestController
@RequestMapping("/api/v1/me/credentials")
class PersonalCredentialController {

    private final PersonalCredentials credentials;
    private final AccessPolicy access;

    PersonalCredentialController(PersonalCredentials credentials, AccessPolicy access) {
        this.credentials = credentials;
        this.access = access;
    }

    @Operation(summary = "Your credentials; secrets are never returned, only a hint")
    @GetMapping
    List<PersonalCredential> list(Principal principal) {
        return credentials.list(account(principal));
    }

    @Operation(summary = "Executor kinds a person can bring a key for")
    @GetMapping("/kinds")
    java.util.Map<String, Object> kinds(Principal principal) {
        access.requireHuman(principal, "manage personal credentials");
        return java.util.Map.of(
                "executors",
                new java.util.TreeSet<>(
                        io.github.loopforge4j.core.runtime.executor.PersonalCredentialVariables.kinds()),
                "variables",
                "*_API_KEY, *_AUTH_TOKEN, *_BASE_URL, *_MODEL, *_ENDPOINT, … (not PATH, LD_*, NODE_*, GIT_*, LOOPFORGE_*)");
    }

    @Operation(
            summary = "Save a credential: executor:<kind> {apiKey, baseUrl, variables} or git:<host> {token,"
                    + " username}; omitted secrets keep the stored ones")
    @PutMapping("/{kind}")
    PersonalCredential save(
            @PathVariable String kind, @RequestBody PersonalCredentials.Input body, Principal principal) {
        return credentials.save(account(principal), kind, body);
    }

    @DeleteMapping("/{kind}")
    ResponseEntity<Void> delete(@PathVariable String kind, Principal principal) {
        return credentials.delete(account(principal), kind)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private String account(Principal principal) {
        CurrentUser user = access.requireHuman(principal, "manage personal credentials");
        if (user.service()) {
            throw new AccessPolicy.AccessDeniedException("the service token has no account");
        }
        return user.id();
    }
}
