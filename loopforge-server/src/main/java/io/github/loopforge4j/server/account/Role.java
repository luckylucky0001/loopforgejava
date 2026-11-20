package io.github.loopforge4j.server.account;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Platform role of an account. Administrators manage users, the global catalog and every project. */
public enum Role {
    ADMIN,
    MEMBER;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Role fromWire(String value) {
        return value == null ? MEMBER : Role.valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
