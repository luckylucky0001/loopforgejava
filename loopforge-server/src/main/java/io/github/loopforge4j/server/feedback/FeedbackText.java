package io.github.loopforge4j.server.feedback;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cleaning of what the widget sends (upstream {@code sanitizeFeedbackURL} and friends): one-line
 * text, length limits counted in code points, and URLs without credentials or credential-looking
 * query parameters (also inside a hash route such as {@code #/runs?token=x}).
 */
final class FeedbackText {

    /** Short words only match whole names or a {@code _}/{@code -} suffix; long ones anywhere. */
    private static final List<String> EXACT = List.of("code", "key", "sig", "pwd", "auth");

    private static final List<String> FRAGMENTS = List.of(
            "token", "ticket", "session", "secret", "password", "passwd", "credential", "signature", "apikey", "nonce");

    private FeedbackText() {}

    static boolean sensitive(String name) {
        String n = name.strip().toLowerCase(Locale.ROOT);
        for (String key : EXACT) {
            if (n.equals(key) || n.endsWith("_" + key) || n.endsWith("-" + key)) {
                return true;
            }
        }
        for (String key : FRAGMENTS) {
            if (n.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** Drops credential-looking parameters and keeps the order of the rest. */
    static String stripQuery(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String part : raw.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String name = eq < 0 ? part : part.substring(0, eq);
            try {
                name = URLDecoder.decode(name, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // keep the raw name
            }
            if (!sensitive(name)) {
                kept.add(part);
            }
        }
        return String.join("&", kept);
    }

    /** A hash route such as {@code #/runs?token=x}. */
    static String route(String route) {
        String r = singleLine(route);
        int q = r.indexOf('?');
        if (q < 0) {
            return r;
        }
        String query = stripQuery(r.substring(q + 1));
        return query.isEmpty() ? r.substring(0, q) : r.substring(0, q) + "?" + query;
    }

    /** An http(s) URL without userinfo or sensitive parameters; anything else becomes empty. */
    static String url(String raw) {
        String value = singleLine(raw);
        if (value.isEmpty()) {
            return "";
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
                return "";
            }
            StringBuilder out = new StringBuilder(scheme).append("://").append(uri.getHost());
            if (uri.getPort() >= 0) {
                out.append(':').append(uri.getPort());
            }
            out.append(uri.getRawPath() == null ? "" : uri.getRawPath());
            String query = stripQuery(uri.getRawQuery());
            if (!query.isEmpty()) {
                out.append('?').append(query);
            }
            if (uri.getRawFragment() != null && !uri.getRawFragment().isEmpty()) {
                String fragment = uri.getRawFragment();
                try {
                    fragment = URLDecoder.decode(fragment, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    // keep as sent
                }
                out.append('#').append(route(fragment));
            }
            return out.toString();
        } catch (java.net.URISyntaxException e) {
            return "";
        }
    }

    /** Control characters become spaces; the result is trimmed. */
    static String singleLine(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> out.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp));
        return out.toString().strip();
    }

    /** At most {@code n} code points. */
    static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        if (s.codePointCount(0, s.length()) <= n) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    static int length(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }
}
