package io.github.loopforge4j.core.runtime;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Keeps credential values out of what a run records: executor telemetry, script output and node
 * results are visible to every project member, while the keys and tokens in a process environment
 * may belong to one person.
 */
public final class Secrets {

    private static final Pattern SECRET_NAME =
            Pattern.compile("(?i).*(TOKEN|_KEY|APIKEY|SECRET|PASSWORD|CREDENTIAL).*");
    private static final int MIN_LENGTH = 8;
    public static final String MASK = "***";

    private Secrets() {}

    /** The values of secret-looking variables (long enough not to mask ordinary words). */
    public static Set<String> values(Map<String, String> environment) {
        Set<String> out =
                new TreeSet<>(Comparator.comparingInt(String::length).reversed().thenComparing(s -> s));
        if (environment == null) {
            return out;
        }
        environment.forEach((name, value) -> {
            if (value != null
                    && value.length() >= MIN_LENGTH
                    && !name.startsWith("GIT_CONFIG_")
                    && SECRET_NAME.matcher(name).matches()) {
                out.add(value);
            }
        });
        return out;
    }

    public static String redact(String text, Collection<String> secrets) {
        if (text == null || secrets.isEmpty()) {
            return text;
        }
        String out = text;
        for (String secret : secrets) {
            out = out.replace(secret, MASK);
        }
        return out;
    }

    /** A sink that masks {@code secrets} in messages and text attributes. */
    public static EventSink redacting(EventSink sink, Collection<String> secrets) {
        if (secrets.isEmpty()) {
            return sink;
        }
        List<String> ordered = List.copyOf(secrets);
        return telemetry -> {
            Map<String, Object> attributes = new LinkedHashMap<>();
            telemetry.attributes().forEach((k, v) -> attributes.put(k, v instanceof String s ? redact(s, ordered) : v));
            sink.emit(new Telemetry(
                    telemetry.category(),
                    telemetry.name(),
                    telemetry.phase(),
                    telemetry.level(),
                    redact(telemetry.message(), ordered),
                    telemetry.sessionId(),
                    telemetry.toolCallId(),
                    telemetry.usage(),
                    attributes));
        };
    }
}
