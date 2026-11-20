package io.github.loopforge4j.server.account;

import io.github.loopforge4j.core.engine.WorkflowValidationException;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account use cases: first-run setup (the first account is an administrator), login with lockout,
 * password changes and token management.
 */
@Service
public class AccountService {

    static final int MIN_PASSWORD = 10;
    /** Names used by service and system actors in run records. */
    static final java.util.Set<String> RESERVED = java.util.Set.of("operator", "system", "schedule", "anonymous");

    private static final int MAX_FAILURES = 5;
    /** Bound of the per-instance failure table (unknown usernames would otherwise grow it). */
    private static final int MAX_TRACKED = 10_000;

    private static final Duration LOCKOUT = Duration.ofMinutes(5);
    /** Same work for unknown usernames as for wrong passwords, so timing does not reveal accounts. */
    private static final String DUMMY_HASH =
            PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("loopforge-dummy-password");

    private final UserStore users;
    private final TokenStore tokens;
    private final Clock clock;
    private final Duration sessionTtl;
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    private record Failures(int count, Instant lockedUntil) {}

    public AccountService(UserStore users, TokenStore tokens, Clock clock, LoopForgeProperties properties) {
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
        this.sessionTtl = properties.security().sessionTtl();
    }

    /** A session: the bearer token and the account it belongs to. */
    public record Session(String token, Instant expiresAt, User user) {}

    public boolean setupRequired() {
        return users.count() == 0;
    }

    @Transactional
    public Session setup(String username, String password, String displayName, String email) {
        if (!setupRequired()) {
            throw new AccessPolicy.AccessDeniedException("setup has already been completed");
        }
        User admin = create(username, password, displayName, email, Role.ADMIN);
        // the primary key makes a concurrent second setup fail and roll back its account
        try {
            users.markSetupCompleted(admin.username());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new AccessPolicy.AccessDeniedException("setup has already been completed");
        }
        return session(admin);
    }

    public Session login(String username, String password) {
        String name = normalize(username);
        Instant now = clock.instant();
        Failures f = failures.get(name);
        if (f != null && f.lockedUntil() != null && f.lockedUntil().isAfter(now)) {
            throw new LoginException("too many failed attempts; try again later");
        }
        User user = users.byUsername(name).orElse(null);
        String hash = user == null ? DUMMY_HASH : users.passwordHash(user.id()).orElse(DUMMY_HASH);
        boolean matches = encoder.matches(password == null ? "" : password, hash);
        if (user == null || !matches || user.disabled()) {
            if (failures.size() > MAX_TRACKED) {
                failures.values()
                        .removeIf(
                                x -> x.lockedUntil() == null || x.lockedUntil().isBefore(now));
                if (failures.size() > MAX_TRACKED) {
                    failures.clear();
                }
            }
            failures.compute(name, (k, old) -> {
                int count = old == null ? 1 : old.count() + 1;
                return new Failures(count, count >= MAX_FAILURES ? now.plus(LOCKOUT) : null);
            });
            throw new LoginException("invalid username or password");
        }
        failures.remove(name);
        if (encoder.upgradeEncoding(hash)) {
            users.setPassword(user.id(), encoder.encode(password));
        }
        return session(user);
    }

    public void logout(String token) {
        tokens.revokeToken(token);
    }

    public User create(String username, String password, String displayName, String email, Role role) {
        String name = normalize(username);
        List<String> problems = new ArrayList<>();
        if (!name.matches("[a-z0-9][a-z0-9._-]{1,63}")) {
            problems.add("username must be 2-64 characters of a-z, 0-9, '.', '_' or '-'");
        } else if (RESERVED.contains(name)) {
            problems.add("username '" + name + "' is reserved");
        }
        checkPassword(password, problems);
        if (email != null && !email.isBlank() && !email.matches("[^@\\s]{1,200}@[^@\\s]{1,200}")) {
            problems.add("email is invalid");
        }
        if (displayName != null && displayName.length() > 200) {
            problems.add("displayName is too long");
        }
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
        if (users.byUsername(name).isPresent()) {
            throw new io.github.loopforge4j.core.engine.RunStateException("username '" + name + "' is taken");
        }
        return users.create(name, displayName, blankToNull(email), role, encoder.encode(password));
    }

    /** A session for an account that signed in elsewhere (single sign-on). */
    public Session sessionFor(User user) {
        if (user.disabled()) {
            throw new LoginException("account is disabled");
        }
        return session(user);
    }

    /**
     * A member account for a single sign-on identity. It has no usable password until an
     * administrator sets one.
     */
    public User createExternal(String username, String displayName, String email) {
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        return users.create(
                normalize(username),
                displayName,
                blankToNull(email),
                Role.MEMBER,
                encoder.encode(java.util.HexFormat.of().formatHex(random)));
    }

    public void changePassword(String userId, String current, String next) {
        String hash = users.passwordHash(userId).orElseThrow();
        if (!encoder.matches(current == null ? "" : current, hash)) {
            throw new LoginException("current password is wrong");
        }
        resetPassword(userId, next);
    }

    /** Sets a new password and ends every session and token of the account. */
    public void resetPassword(String userId, String next) {
        List<String> problems = new ArrayList<>();
        checkPassword(next, problems);
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
        users.setPassword(userId, encoder.encode(next));
        tokens.revokeAll(userId);
    }

    private Session session(User user) {
        Instant expires = clock.instant().plus(sessionTtl);
        TokenStore.Issued issued = tokens.issue(user.id(), "login", "session", expires);
        return new Session(issued.token(), expires, user);
    }

    private static void checkPassword(String password, List<String> problems) {
        if (password == null || password.length() < MIN_PASSWORD) {
            problems.add("password must have at least " + MIN_PASSWORD + " characters");
        } else if (password.length() > 200) {
            problems.add("password is too long");
        }
    }

    static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Failed authentication (401). */
    public static class LoginException extends RuntimeException {
        public LoginException(String message) {
            super(message);
        }
    }
}
