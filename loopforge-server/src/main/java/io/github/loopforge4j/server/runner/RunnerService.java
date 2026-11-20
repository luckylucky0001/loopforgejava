package io.github.loopforge4j.server.runner;

import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Registration, authentication and authorization of execution machines. */
@Service
public class RunnerService {

    public static final String TOKEN_PREFIX = "lfx_";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A new machine and its token, shown once. */
    public record Registered(Runner runner, String token) {}

    private final RunnerStore store;
    private final AccessPolicy access;
    private final Clock clock;

    public RunnerService(RunnerStore store, AccessPolicy access, Clock clock) {
        this.store = store;
        this.access = access;
        this.clock = clock;
    }

    /** A personal machine of the caller, or (administrators) a pool machine. */
    public Registered register(String name, boolean shared, Principal principal) {
        CurrentUser user = access.requireHuman(principal, "register machines");
        if (shared && !user.admin()) {
            throw new AccessPolicy.AccessDeniedException("only administrators register pool machines");
        }
        if (!shared && user.id() == null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "a personal machine belongs to an account; use shared=true");
        }
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 200) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "name is required (at most 200 characters)");
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String id = "rnr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        store.create(id, clean, shared ? null : user.id(), shared, hash(token), token.substring(0, 10));
        return new Registered(store.find(id).orElseThrow(), token);
    }

    /** Machines the caller may see and start runs on: their own and the pool (administrators: all). */
    public List<Runner> visible(Principal principal) {
        CurrentUser user = access.current(principal);
        return store.list().stream()
                .filter(r -> user.admin()
                        || r.shared()
                        || user.id() != null && user.id().equals(r.ownerId()))
                .toList();
    }

    public void delete(String id, Principal principal) {
        Runner runner = store.find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "runner not found"));
        CurrentUser user = access.current(principal);
        if (!user.admin() && (runner.ownerId() == null || !runner.ownerId().equals(user.id()))) {
            throw new AccessPolicy.AccessDeniedException("only the owner or an administrator removes a machine");
        }
        store.delete(id);
    }

    /**
     * The machine a run may be assigned to: the caller's own, or a pool machine. Project access is
     * checked by the run start itself.
     */
    public Runner requireUsable(String runnerId, Principal principal) {
        CurrentUser user = access.current(principal);
        Runner runner = store.find(runnerId)
                .orElseThrow(
                        () -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "runner " + runnerId + " not found"));
        boolean own = runner.ownerId() != null && runner.ownerId().equals(user.id());
        if (!runner.shared() && !own) {
            throw new AccessPolicy.AccessDeniedException("runner " + runnerId + " belongs to someone else");
        }
        return runner;
    }

    public Optional<Runner> authenticate(String bearer) {
        if (bearer == null || !bearer.startsWith(TOKEN_PREFIX) || bearer.length() > 200) {
            return Optional.empty();
        }
        return store.byTokenHash(hash(bearer));
    }

    void heartbeat(Runner runner, RunnerStore.Status status) {
        store.seen(runner.id(), status);
    }

    boolean online(Runner runner) {
        return runner.online(clock.instant());
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
