package io.github.loopforge4j.server.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Login, first-run setup, the caller's account and tokens, and account administration. */
@Tag(name = "Accounts")
@RestController
@RequestMapping("/api/v1")
class AccountController {

    private final AccountService accounts;
    private final UserStore users;
    private final TokenStore tokens;
    private final ProjectStore projects;
    private final AccessPolicy access;

    private final OidcService oidc;

    AccountController(
            AccountService accounts,
            UserStore users,
            TokenStore tokens,
            ProjectStore projects,
            AccessPolicy access,
            OidcService oidc) {
        this.oidc = oidc;
        this.accounts = accounts;
        this.users = users;
        this.tokens = tokens;
        this.projects = projects;
        this.access = access;
    }

    record Credentials(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 200) String password) {}

    record NewAccount(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 200) String password,
            @Size(max = 200) String displayName,
            @Size(max = 320) String email,
            Role role) {}

    record AccountUpdate(
            @Size(max = 200) String displayName,
            @Size(max = 320) String email,
            Role role,
            Boolean disabled,
            @Size(max = 200) String password) {}

    record PasswordChange(
            @NotBlank String currentPassword,
            @NotBlank @Size(max = 200) String newPassword) {}

    record NewToken(@NotBlank @Size(max = 200) String name, Instant expiresAt) {}

    @Operation(summary = "Whether the first administrator still has to be created")
    @GetMapping("/auth/status")
    Map<String, Object> status() {
        return Map.of("setupRequired", accounts.setupRequired(), "oidc", oidc.status());
    }

    @Operation(summary = "Create the first administrator (only while no account exists)")
    @PostMapping("/auth/setup")
    ResponseEntity<AccountService.Session> setup(@Valid @RequestBody NewAccount body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accounts.setup(body.username(), body.password(), body.displayName(), body.email()));
    }

    @PostMapping("/auth/login")
    AccountService.Session login(@Valid @RequestBody Credentials body) {
        return accounts.login(body.username(), body.password());
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            accounts.logout(authorization.substring(7));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    Map<String, Object> me(Principal principal) {
        CurrentUser user = access.current(principal);
        if (user.service()) {
            return Map.of("username", user.username(), "role", user.role(), "service", true, "projects", List.of());
        }
        if (user.agent() != null) {
            return Map.of(
                    "username", user.username(),
                    "service", false,
                    "agent", user.agent(),
                    "projects", access.rolesOf(user));
        }
        return Map.of(
                "user", users.byId(user.id()).orElseThrow(),
                "service", false,
                "projects", projects.rolesOf(user.id()));
    }

    @PostMapping("/me/password")
    ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChange body, Principal principal) {
        accounts.changePassword(account(principal), body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/tokens")
    List<AccessToken> myTokens(Principal principal) {
        return tokens.list(account(principal));
    }

    @Operation(summary = "Create a personal access token; the secret is returned only once")
    @PostMapping("/me/tokens")
    ResponseEntity<TokenStore.Issued> createToken(@Valid @RequestBody NewToken body, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tokens.issue(account(principal), body.name(), "personal", body.expiresAt()));
    }

    @DeleteMapping("/me/tokens/{id}")
    ResponseEntity<Void> revokeToken(@PathVariable String id, Principal principal) {
        return tokens.revoke(account(principal), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/users")
    List<User> listUsers(Principal principal) {
        access.requireAdmin(principal);
        return users.list();
    }

    @PostMapping("/users")
    ResponseEntity<User> createUser(@Valid @RequestBody NewAccount body, Principal principal) {
        access.requireAdmin(principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accounts.create(
                        body.username(),
                        body.password(),
                        body.displayName(),
                        body.email(),
                        body.role() == null ? Role.MEMBER : body.role()));
    }

    @PutMapping("/users/{id}")
    User updateUser(@PathVariable String id, @Valid @RequestBody AccountUpdate body, Principal principal) {
        access.requireAdmin(principal);
        User existing = users.byId(id)
                .orElseThrow(() ->
                        new io.github.loopforge4j.core.engine.RunNotFoundException("user '" + id + "' not found"));
        Role role = body.role() == null ? existing.role() : body.role();
        boolean disabled = body.disabled() == null ? existing.disabled() : body.disabled();
        if (existing.admin() && (role != Role.ADMIN || disabled) && users.countAdmins() <= 1) {
            throw new io.github.loopforge4j.core.engine.RunStateException("the last administrator cannot be demoted");
        }
        User updated = users.update(
                id,
                body.displayName() == null ? existing.displayName() : body.displayName(),
                body.email() == null ? existing.email() : body.email(),
                role,
                disabled);
        if (body.password() != null) {
            accounts.resetPassword(id, body.password());
        } else if (disabled) {
            tokens.revokeAll(id);
        }
        return updated;
    }

    @DeleteMapping("/users/{id}")
    ResponseEntity<Void> deleteUser(@PathVariable String id, Principal principal) {
        access.requireAdmin(principal);
        User existing = users.byId(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.admin() && users.countAdmins() <= 1) {
            throw new io.github.loopforge4j.core.engine.RunStateException("the last administrator cannot be deleted");
        }
        users.delete(id);
        return ResponseEntity.noContent().build();
    }

    private String account(Principal principal) {
        CurrentUser user = access.current(principal);
        if (user.service()) {
            throw new AccessPolicy.AccessDeniedException("the service token has no account");
        }
        return user.id();
    }
}
