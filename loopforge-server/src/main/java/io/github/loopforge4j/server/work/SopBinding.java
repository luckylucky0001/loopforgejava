package io.github.loopforge4j.server.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One step of a requirement's SOP chain (upstream {@code IssueSOPBinding}): an SOP lane with its
 * order, owner, the steps it continues from and the deliverables it expects from them.
 *
 * @param assignee the person who owns the lane (shown; runs still act for whoever starts them)
 * @param dependsOn ids of the steps this one continues from (default: the previous step)
 * @param handoff deliverables expected from those steps, named in the run's input
 */
public record SopBinding(
        String id, String workflowId, int order, String assignee, List<String> dependsOn, List<String> handoff) {

    public SopBinding {
        id = id == null ? null : id.strip();
        workflowId = workflowId == null ? null : workflowId.strip();
        assignee = assignee == null || assignee.isBlank() ? null : assignee.strip();
        dependsOn = compact(dependsOn);
        handoff = compact(handoff);
    }

    /**
     * Upstream {@code NormalizeSOPBindings}: one step per SOP, missing ids derived from the SOP,
     * sorted by order (stable), orders renumbered from 1, dependencies on unknown steps dropped.
     */
    public static List<SopBinding> normalize(List<SopBinding> in) {
        if (in == null) {
            return null;
        }
        record Item(SopBinding binding, int index) {}
        Set<String> seen = new HashSet<>();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            SopBinding b = in.get(i);
            if (b == null || b.workflowId() == null || b.workflowId().isEmpty() || !seen.add(b.workflowId())) {
                continue;
            }
            String id = b.id() == null || b.id().isEmpty() ? "sop-" + b.workflowId() : b.id();
            int order = b.order() <= 0 ? (i + 1) * 10 : b.order();
            items.add(new Item(new SopBinding(id, b.workflowId(), order, b.assignee(), b.dependsOn(), b.handoff()), i));
        }
        items.sort(Comparator.comparingInt((Item it) -> it.binding().order()).thenComparingInt(Item::index));
        Set<String> ids = new HashSet<>();
        items.forEach(it -> ids.add(it.binding().id()));
        List<SopBinding> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            SopBinding b = items.get(i).binding();
            List<String> deps = b.dependsOn().stream()
                    .filter(d -> ids.contains(d) && !d.equals(b.id()))
                    .toList();
            out.add(new SopBinding(b.id(), b.workflowId(), i + 1, b.assignee(), deps, b.handoff()));
        }
        return List.copyOf(out);
    }

    private static List<String> compact(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.strip());
            }
        }
        return List.copyOf(out);
    }
}
