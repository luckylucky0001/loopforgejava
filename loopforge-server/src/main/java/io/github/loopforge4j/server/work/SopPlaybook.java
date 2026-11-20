package io.github.loopforge4j.server.work;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A named, reusable SOP chain of a project (upstream {@code SOPPlaybook}), e.g. 完整需求 or 仅后端;
 * new requirements start with the default one.
 */
public record SopPlaybook(
        String id,
        String name,

        @com.fasterxml.jackson.annotation.JsonProperty("default")
        boolean isDefault,

        List<SopBinding> bindings) {

    public SopPlaybook {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    /** Upstream {@code NormalizeSOPPlaybooks}: unique ids, names, normalized chains, exactly one default. */
    public static List<SopPlaybook> normalize(List<SopPlaybook> in) {
        if (in == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<SopPlaybook> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            SopPlaybook book = in.get(i);
            if (book == null) {
                continue;
            }
            String name = book.name() == null || book.name().isBlank()
                    ? "未命名套餐"
                    : book.name().strip();
            String id =
                    book.id() == null || book.id().isBlank() ? null : book.id().strip();
            if (id == null) {
                id = book.name() == null || book.name().isBlank() ? "pb-" + (i + 1) : "pb-" + name;
            }
            if (!seen.add(id)) {
                continue;
            }
            out.add(new SopPlaybook(id, name, book.isDefault(), SopBinding.normalize(book.bindings())));
        }
        boolean found = false;
        for (int i = 0; i < out.size(); i++) {
            SopPlaybook b = out.get(i);
            boolean keep = b.isDefault() && !found;
            found |= keep;
            out.set(i, new SopPlaybook(b.id(), b.name(), keep, b.bindings()));
        }
        if (!found && !out.isEmpty()) {
            SopPlaybook first = out.getFirst();
            out.set(0, new SopPlaybook(first.id(), first.name(), true, first.bindings()));
        }
        return List.copyOf(out);
    }
}
