package io.github.loopforge4j.server.account;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.executor.PersonalCredentialVariables;
import io.github.loopforge4j.core.runtime.git.GitCredentials;
import io.github.loopforge4j.server.persistence.SqlTime;
import io.github.loopforge4j.server.web.ApiException;
import io.github.loopforge4j.server.work.Schedule;
import io.github.loopforge4j.server.work.ScheduleStore;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Personal credentials (upstream personal settings): a person's own model key, base URL and extra
 * variables per executor kind, and tokens per Git host. Work done for a person (runs they started,
 * scheduled runs they created, their conversation turns and clarification rounds) uses these over
 * the deployment's. Secrets are encrypted at rest and never returned.
 */
@Service
public class PersonalCredentials {

    public static final String EXECUTOR = "executor:";
    public static final String GIT = "git:";
    private static final Pattern HOST =
            Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*(:\\d{1,5})?$");
    private static final int MAX_SECRET = 4096;
    private static final int MAX_VARIABLES = 20;
    private static final String SCHEDULE = "schedule:";

    private final JdbcClient jdbc;
    private final CredentialCipher cipher;
    private final UserStore users;
    private final ObjectProvider<ScheduleStore> schedules;
    private final Clock clock;

    public PersonalCredentials(
            JdbcClient jdbc,
            CredentialCipher cipher,
            UserStore users,
            ObjectProvider<ScheduleStore> schedules,
            Clock clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.users = users;
        this.schedules = schedules;
        this.clock = clock;
    }

    /** What a person sends; {@code null} secrets keep the stored ones. */
    public record Input(String apiKey, String baseUrl, String token, String username, Map<String, String> variables) {}

    private record Secret(String apiKey, String token, Map<String, String> variables) {}

    private record Public(String baseUrl, String username, String hint, List<String> variables) {}

    private record Row(String kind, Secret secret, Public info, java.time.Instant updatedAt) {}

    // ------------------------------------------------------------------ the person's own view

    public List<PersonalCredential> list(String userId) {
        return rows(userId).stream()
                .map(r -> new PersonalCredential(
                        r.kind(),
                        r.info().baseUrl(),
                        r.info().username(),
                        r.info().hint(),
                        r.info().variables(),
                        r.updatedAt()))
                .toList();
    }

    public PersonalCredential save(String userId, String kind, Input input) {
        String k = kind == null ? "" : kind.strip();
        Optional<Row> existing =
                rows(userId).stream().filter(r -> r.kind().equals(k)).findFirst();
        Secret secret;
        Public info;
        if (k.startsWith(EXECUTOR) && PersonalCredentialVariables.kinds().contains(k.substring(EXECUTOR.length()))) {
            String apiKey = input.apiKey() != null
                    ? secretValue(input.apiKey(), "apiKey")
                    : existing.map(r -> r.secret().apiKey()).orElse(null);
            Map<String, String> variables = input.variables() != null
                    ? variables(input.variables())
                    : existing.map(r -> r.secret().variables()).orElse(Map.of());
            if ((apiKey == null || apiKey.isEmpty()) && variables.isEmpty()) {
                throw invalid("an executor credential needs apiKey or variables");
            }
            String baseUrl = input.baseUrl() != null
                    ? baseUrl(input.baseUrl())
                    : existing.map(r -> r.info().baseUrl()).orElse(null);
            secret = new Secret(apiKey == null || apiKey.isEmpty() ? null : apiKey, null, variables);
            info = new Public(baseUrl, null, hint(apiKey), List.copyOf(new TreeMap<>(variables).keySet()));
        } else if (k.startsWith(GIT) && HOST.matcher(k.substring(GIT.length())).matches()) {
            String token = input.token() != null
                    ? secretValue(input.token(), "token")
                    : existing.map(r -> r.secret().token()).orElse(null);
            if (token == null || token.isEmpty()) {
                throw invalid("a Git credential needs a token");
            }
            String username = input.username() != null
                    ? plain(input.username(), 200, "username")
                    : existing.map(r -> r.info().username()).orElse(null);
            secret = new Secret(null, token, Map.of());
            info = new Public(null, username == null || username.isEmpty() ? null : username, hint(token), List.of());
        } else {
            throw invalid("kind must be executor:<"
                    + String.join("|", new java.util.TreeSet<>(PersonalCredentialVariables.kinds()))
                    + "> or git:<host>");
        }
        java.time.Instant now = clock.instant();
        String sealed = cipher.encrypt(Json.write(secret), binding(userId, k));
        String sql = existing.isPresent()
                ? "UPDATE user_credentials SET secret = :secret, public_info = :info, updated_at = :now"
                        + " WHERE user_id = :u AND kind = :k"
                : "INSERT INTO user_credentials (user_id, kind, secret, public_info, updated_at)"
                        + " VALUES (:u, :k, :secret, :info, :now)";
        jdbc.sql(sql)
                .param("u", userId)
                .param("k", k)
                .param("secret", sealed)
                .param("info", Json.write(info))
                .param("now", SqlTime.of(now))
                .update();
        return new PersonalCredential(k, info.baseUrl(), info.username(), info.hint(), info.variables(), now);
    }

    public boolean delete(String userId, String kind) {
        return jdbc.sql("DELETE FROM user_credentials WHERE user_id = :u AND kind = :k")
                        .param("u", userId)
                        .param("k", kind)
                        .update()
                > 0;
    }

    // ------------------------------------------------------------------ resolution for work

    /**
     * The account work is done for: a username as recorded on runs, or {@code schedule:<id>} (the
     * account that last saved the schedule).
     */
    public Optional<String> accountOf(String responsible) {
        if (responsible == null || responsible.isBlank()) {
            return Optional.empty();
        }
        String name = responsible.strip();
        if (name.startsWith(SCHEDULE)) {
            name = schedules
                    .getObject()
                    .find(name.substring(SCHEDULE.length()))
                    .map(Schedule::responsible)
                    .orElse(null);
            if (name == null) {
                return Optional.empty();
            }
        }
        return users.byUsername(name).filter(u -> !u.disabled()).map(User::id);
    }

    /** The person's environment for an executor kind; empty when they brought none. */
    public Map<String, String> executorEnvironment(String executorKind, String responsible) {
        Optional<String> user = accountOf(responsible);
        if (user.isEmpty()) {
            return Map.of();
        }
        return rows(user.get()).stream()
                .filter(r -> r.kind().equals(EXECUTOR + executorKind))
                .findFirst()
                .map(r -> PersonalCredentialVariables.environment(
                        executorKind,
                        r.secret().apiKey(),
                        r.info().baseUrl(),
                        r.secret().variables()))
                .orElse(Map.of());
    }

    /** The person's Git tokens per host. */
    public Map<String, GitCredentials.HttpsCredential> gitHttps(String responsible) {
        Optional<String> user = accountOf(responsible);
        if (user.isEmpty()) {
            return Map.of();
        }
        Map<String, GitCredentials.HttpsCredential> out = new LinkedHashMap<>();
        for (Row r : rows(user.get())) {
            if (r.kind().startsWith(GIT) && r.secret().token() != null) {
                out.put(
                        r.kind().substring(GIT.length()),
                        new GitCredentials.HttpsCredential(
                                r.info().username(), r.secret().token()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private record Stored(String kind, String secret, String info, java.time.Instant updatedAt) {}

    private List<Row> rows(String userId) {
        List<Stored> stored = jdbc.sql(
                        "SELECT kind, secret, public_info, updated_at FROM user_credentials WHERE user_id = :u ORDER BY kind")
                .param("u", userId)
                .query((rs, i) -> new Stored(
                        rs.getString("kind"),
                        rs.getString("secret"),
                        rs.getString("public_info"),
                        SqlTime.read(rs, "updated_at")))
                .list();
        List<Row> out = new ArrayList<>();
        for (Stored s : stored) {
            // a row that does not open (another key, a row moved between accounts) is treated as absent
            cipher.decrypt(s.secret(), binding(userId, s.kind()))
                    .map(text -> Json.read(text, Secret.class))
                    .ifPresent(secret ->
                            out.add(new Row(s.kind(), secret, Json.read(s.info(), Public.class), s.updatedAt())));
        }
        return out;
    }

    private static String binding(String userId, String kind) {
        return userId + "\n" + kind;
    }

    private static String hint(String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        return secret.length() <= 8 ? "…" : "…" + secret.substring(secret.length() - 4);
    }

    private static Map<String, String> variables(Map<String, String> input) {
        if (input.size() > MAX_VARIABLES) {
            throw invalid("at most " + MAX_VARIABLES + " variables");
        }
        Map<String, String> out = new TreeMap<>();
        input.forEach((name, value) -> {
            if (!PersonalCredentialVariables.allowed(name)) {
                throw invalid("variable " + name + " is not allowed: use credential names such as *_API_KEY,"
                        + " *_AUTH_TOKEN, *_BASE_URL or *_MODEL (not PATH, LD_*, NODE_*, GIT_*, LOOPFORGE_*, …)");
            }
            out.put(name, secretValue(value, name));
        });
        return out;
    }

    private static String secretValue(String value, String field) {
        String v = value == null ? "" : value.strip();
        if (v.length() > MAX_SECRET || v.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw invalid(field + " must be a single line of at most " + MAX_SECRET + " characters");
        }
        return v;
    }

    private static String plain(String value, int max, String field) {
        String v = value.strip();
        if (v.length() > max || v.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw invalid(field + " must be a single line of at most " + max + " characters");
        }
        return v;
    }

    private static String baseUrl(String value) {
        String v = plain(value, 500, "baseUrl");
        if (v.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(v);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw invalid("baseUrl must be an http(s) URL without credentials");
            }
        } catch (IllegalArgumentException e) {
            throw invalid("baseUrl must be an http(s) URL without credentials");
        }
        return v;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }
}
