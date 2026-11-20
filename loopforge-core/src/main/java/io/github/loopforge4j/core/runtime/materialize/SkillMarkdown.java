package io.github.loopforge4j.core.runtime.materialize;

import java.util.ArrayList;
import java.util.List;

/** SKILL.md front matter handling (upstream {@code skill_md.go}), workspace copies only. */
final class SkillMarkdown {

    private SkillMarkdown() {}

    /** Ensures a closed front matter whose {@code name} is the skill id and that has a description. */
    static String ensureFrontmatter(String raw, String id, String description) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n");
        String[] split = split(text);
        if (split == null) {
            StringBuilder b = new StringBuilder("---\nname: ")
                    .append(scalar(id))
                    .append("\ndescription: ")
                    .append(scalar(description))
                    .append("\n---\n");
            String body = text.strip();
            if (!body.isEmpty()) {
                b.append('\n').append(body).append('\n');
            }
            return b.toString();
        }
        List<String> header = new ArrayList<>(List.of(split[0].split("\n", -1)));
        header.removeIf(String::isEmpty);
        set(header, "name", id, true);
        if (field(header, "description").isBlank() && !description.isBlank()) {
            set(header, "description", description, true);
        }
        return "---\n" + String.join("\n", header) + "\n---\n" + split[1];
    }

    static String description(String body) {
        String[] split = split(body);
        return split == null ? "" : field(List.of(split[0].split("\n")), "description");
    }

    /** Validates a skill file path: relative, no traversal, portable characters. */
    static String checkPath(String path) {
        String p = path == null ? "" : path.strip();
        if (p.isEmpty()
                || p.length() > 300
                || p.startsWith("/")
                || p.contains("\\")
                || p.contains("\0")
                || !p.matches("[A-Za-z0-9._/@+ -]+")) {
            throw new IllegalArgumentException("invalid skill file path: " + path);
        }
        for (String segment : p.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("invalid skill file path: " + path);
            }
        }
        return p;
    }

    /** [header, rest] when the text starts with a closed {@code ---} block, else {@code null}. */
    private static String[] split(String text) {
        String trimmed = text.stripLeading();
        if (!trimmed.startsWith("---\n")) {
            return null;
        }
        int end = trimmed.indexOf("\n---", 3);
        if (end < 0) {
            return null;
        }
        int after = trimmed.indexOf('\n', end + 4);
        String rest = after < 0 ? "" : trimmed.substring(after + 1);
        return new String[] {trimmed.substring(4, end), rest};
    }

    private static String field(List<String> header, String key) {
        for (String line : header) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).strip().equals(key)) {
                String value = line.substring(colon + 1).strip();
                if (value.length() >= 2
                        && (value.startsWith("\"") && value.endsWith("\"")
                                || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return "";
    }

    private static void set(List<String> header, String key, String value, boolean first) {
        String line = key + ": " + scalar(value);
        for (int i = 0; i < header.size(); i++) {
            int colon = header.get(i).indexOf(':');
            if (colon > 0 && header.get(i).substring(0, colon).strip().equals(key)) {
                header.set(i, line);
                return;
            }
        }
        if (first) {
            header.addFirst(line);
        } else {
            header.add(line);
        }
    }

    /** Double-quoted YAML scalar (JSON string syntax is valid YAML). */
    private static String scalar(String value) {
        return io.github.loopforge4j.core.mcp.MiniJson.write(value == null ? "" : value.replace("\n", " "));
    }
}
