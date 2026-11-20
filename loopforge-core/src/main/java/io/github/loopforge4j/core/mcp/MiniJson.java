package io.github.loopforge4j.core.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer with no dependencies beyond the JDK, so the gate MCP server can run
 * from a two-class jar inside any sandbox. Objects become {@link LinkedHashMap}, arrays {@link
 * ArrayList}, numbers {@link Long} or {@link Double}.
 */
public final class MiniJson {

    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    /** Parses one JSON value; throws {@link IllegalArgumentException} on malformed input. */
    public static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        parser.skipWhitespace();
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw new IllegalArgumentException("trailing characters at " + parser.pos);
        }
        return value;
    }

    private Object value(int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("nesting too deep");
        }
        if (pos >= text.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw new IllegalArgumentException("unexpected character '" + c + "' at " + pos);
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new IllegalArgumentException("expected object key at " + pos);
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, value(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at " + (pos - 1));
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at " + (pos - 1));
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw new IllegalArgumentException("control character in string at " + (pos - 1));
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw new IllegalArgumentException("truncated unicode escape");
                    }
                    b.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("invalid escape at " + (pos - 1));
            }
        }
    }

    private Object number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        boolean fraction = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                fraction = true;
                pos++;
            } else {
                break;
            }
        }
        String raw = text.substring(start, pos);
        try {
            return fraction ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return Double.parseDouble(raw);
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw new IllegalArgumentException("invalid literal at " + pos);
        }
        pos += word.length();
        return value;
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= text.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        return text.charAt(pos++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw new IllegalArgumentException("expected '" + c + "' at " + (pos - 1));
        }
    }

    /** Serializes maps, lists, strings, numbers, booleans and null. */
    public static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value);
        return b.toString();
    }

    private static void write(StringBuilder b, Object value) {
        if (value == null) {
            b.append("null");
        } else if (value instanceof String s) {
            quote(b, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            b.append(value);
        } else if (value instanceof Map<?, ?> map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                quote(b, String.valueOf(entry.getKey()));
                b.append(':');
                write(b, entry.getValue());
            }
            b.append('}');
        } else if (value instanceof Iterable<?> list) {
            b.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                write(b, item);
            }
            b.append(']');
        } else {
            quote(b, value.toString());
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
