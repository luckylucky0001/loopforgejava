package io.github.loopforge4j.server.account;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Role in a project, ordered by privilege: viewers read; developers start and steer runs and edit
 * requirements; maintainers manage the project's repositories, schedules and notifications; owners
 * also manage members.
 */
public enum ProjectRole {
    VIEWER,
    DEVELOPER,
    MAINTAINER,
    OWNER;

    public boolean atLeast(ProjectRole other) {
        return compareTo(other) >= 0;
    }

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ProjectRole fromWire(String value) {
        return ProjectRole.valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
