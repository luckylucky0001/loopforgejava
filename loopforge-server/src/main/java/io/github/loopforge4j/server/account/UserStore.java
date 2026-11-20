package io.github.loopforge4j.server.account;

import io.github.loopforge4j.server.persistence.SqlTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Accounts. Usernames are stored lower-case and unique. */
@Repository
public class UserStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    public UserStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
    }

    public User create(String username, String displayName, String email, Role role, String passwordHash) {
        var now = clock.instant();
        User user = new User(
                "usr_" + UUID.randomUUID().toString().replace("-", ""),
                username,
                displayName == null || displayName.isBlank() ? username : displayName.strip(),
                email,
                role,
                false,
                now,
                now);
        jdbc.sql("INSERT INTO users (id, username, display_name, email, password_hash, role, disabled, created_at,"
                        + " updated_at) VALUES (:id, :username, :displayName, :email, :hash, :role, FALSE, :now, :now)")
                .param("id", user.id())
                .param("username", user.username())
                .param("displayName", user.displayName())
                .param("email", user.email())
                .param("hash", passwordHash)
                .param("role", role.wire())
                .param("now", SqlTime.of(now))
                .update();
        return user;
    }

    /** Accounts with this e-mail address (case-insensitive). */
    public java.util.List<User> byEmail(String email) {
        return jdbc.sql("SELECT * FROM users WHERE LOWER(email) = :email")
                .param("email", email.strip().toLowerCase(java.util.Locale.ROOT))
                .query(UserStore::map)
                .list();
    }

    /** The account an external identity (OIDC issuer and subject) signs in as. */
    public Optional<String> identity(String issuer, String subject) {
        return jdbc.sql("SELECT user_id FROM user_identities WHERE issuer = :iss AND subject = :sub")
                .param("iss", issuer)
                .param("sub", subject)
                .query(String.class)
                .optional();
    }

    public void link(String issuer, String subject, String userId) {
        jdbc.sql("INSERT INTO user_identities (issuer, subject, user_id, created_at) VALUES (:iss, :sub, :user, :now)")
                .param("iss", issuer)
                .param("sub", subject)
                .param("user", userId)
                .param("now", SqlTime.of(clock.instant()))
                .update();
    }

    public Optional<User> byId(String id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id")
                .param("id", id)
                .query(UserStore::map)
                .optional();
    }

    public Optional<User> byUsername(String username) {
        return jdbc.sql("SELECT * FROM users WHERE username = :username")
                .param("username", username)
                .query(UserStore::map)
                .optional();
    }

    public Optional<String> passwordHash(String userId) {
        return jdbc.sql("SELECT password_hash FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    public List<User> list() {
        return jdbc.sql("SELECT * FROM users ORDER BY username")
                .query(UserStore::map)
                .list();
    }

    public User update(String id, String displayName, String email, Role role, boolean disabled) {
        jdbc.sql("UPDATE users SET display_name = :displayName, email = :email, role = :role, disabled = :disabled,"
                        + " updated_at = :now WHERE id = :id")
                .param("id", id)
                .param("displayName", displayName)
                .param("email", email)
                .param("role", role.wire())
                .param("disabled", disabled)
                .param("now", SqlTime.of(clock.instant()))
                .update();
        return byId(id).orElseThrow();
    }

    public void setPassword(String id, String passwordHash) {
        jdbc.sql("UPDATE users SET password_hash = :hash, updated_at = :now WHERE id = :id")
                .param("id", id)
                .param("hash", passwordHash)
                .param("now", SqlTime.of(clock.instant()))
                .update();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM users WHERE id = :id").param("id", id).update() > 0;
    }

    /** Records the completed first-run setup; throws DuplicateKeyException when it already was. */
    public void markSetupCompleted(String username) {
        jdbc.sql(
                        "INSERT INTO app_settings (setting_name, setting_value, updated_at) VALUES ('setup.completed', :value, :now)")
                .param("value", username)
                .param("now", SqlTime.of(clock.instant()))
                .update();
    }

    public long countAdmins() {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'admin' AND disabled = FALSE")
                .query(Long.class)
                .single();
    }

    static User map(ResultSet rs, int row) throws SQLException {
        return new User(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("email"),
                Role.fromWire(rs.getString("role")),
                rs.getBoolean("disabled"),
                SqlTime.read(rs, "created_at"),
                SqlTime.read(rs, "updated_at"));
    }
}
