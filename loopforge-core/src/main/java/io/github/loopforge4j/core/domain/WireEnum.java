package io.github.loopforge4j.core.domain;

import java.util.Locale;

/** Enum whose JSON representation is a stable lower-case wire value shared with upstream. */
public interface WireEnum {

    String wire();

    static <E extends Enum<E> & WireEnum> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.wire().equals(normalized)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("unsupported " + type.getSimpleName() + " value '" + value + "'");
    }
}
