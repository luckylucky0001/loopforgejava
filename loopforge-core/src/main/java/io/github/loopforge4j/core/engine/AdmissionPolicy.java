package io.github.loopforge4j.core.engine;

/**
 * Run admission (upstream run queue buckets): new top-level runs beyond these limits stay {@code
 * queued} and are started oldest first as running runs settle. Waiting runs (parked on a person) and
 * child SOP runs do not count and are never queued. The limits are soft across instances: two
 * instances admitting at the same moment may exceed them by one each.
 *
 * @param maxActiveRuns running top-level runs in the whole deployment, 0 for no limit
 * @param maxActiveRunsPerProject running top-level runs per project, 0 for no limit
 */
public record AdmissionPolicy(int maxActiveRuns, int maxActiveRunsPerProject) {

    public static final AdmissionPolicy UNLIMITED = new AdmissionPolicy(0, 0);

    public AdmissionPolicy {
        maxActiveRuns = Math.max(0, maxActiveRuns);
        maxActiveRunsPerProject = Math.max(0, maxActiveRunsPerProject);
    }

    boolean unlimited() {
        return maxActiveRuns == 0 && maxActiveRunsPerProject == 0;
    }
}
