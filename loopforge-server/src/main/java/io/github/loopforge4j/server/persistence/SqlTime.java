package io.github.loopforge4j.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Public access to the timestamp conversions for stores outside this package. */
public final class SqlTime {

    private SqlTime() {}

    public static OffsetDateTime of(Instant instant) {
        return Timestamps.of(instant);
    }

    public static Instant read(ResultSet rs, String column) throws SQLException {
        return Timestamps.read(rs, column);
    }
}
