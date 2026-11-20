package io.github.loopforge4j.core.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared JSON codec. Lenient on input (unknown properties are ignored so upstream catalog exports
 * load) and compact on output (null values are omitted).
 */
public final class Json {

    private static final JsonMapper MAPPER = configure(JsonMapper.builder()).build();

    private Json() {}

    /** Applies the LoopForge4J conventions to a builder, e.g. the one Spring Boot customizes. */
    public static JsonMapper.Builder configure(JsonMapper.Builder builder) {
        return builder.changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    public static <T> T read(String json, TypeReference<T> type) {
        return MAPPER.readValue(json, type);
    }

    /** Parses one line of a JSON stream; returns {@code null} for blank or non-JSON lines. */
    public static JsonNode tryParseObject(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.strip();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Deep copy through the JSON representation; keeps in-memory stores free of shared state. */
    public static <T> T copy(T value, Class<T> type) {
        return MAPPER.readValue(MAPPER.writeValueAsBytes(value), type);
    }
}
