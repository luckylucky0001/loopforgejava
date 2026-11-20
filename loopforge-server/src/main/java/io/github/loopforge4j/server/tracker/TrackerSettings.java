package io.github.loopforge4j.server.tracker;

import java.time.Instant;
import java.util.List;

/**
 * Which external trackers feed a project's requirements (upstream project TAPD pool and synced
 * repositories).
 *
 * @param issueRepositoryIds catalog repositories (GitHub or GitLab/工蜂) whose issues are pulled
 * @param tapdWorkspaceId TAPD workspace of the requirement pool
 * @param tapdIterationId TAPD iteration whose stories and bugs are pulled
 * @param autoSync include the project in periodic pulls
 * @param writeBack comment on the external item when a run delivers pull requests
 */
public record TrackerSettings(
        List<String> issueRepositoryIds,
        String tapdWorkspaceId,
        String tapdIterationId,
        boolean autoSync,
        boolean writeBack,
        String updatedBy,
        Instant updatedAt) {

    public static final TrackerSettings NONE = new TrackerSettings(List.of(), null, null, true, true, null, null);

    public TrackerSettings {
        issueRepositoryIds = issueRepositoryIds == null ? List.of() : List.copyOf(issueRepositoryIds);
        tapdWorkspaceId = blank(tapdWorkspaceId);
        tapdIterationId = blank(tapdIterationId);
    }

    public boolean tapdPool() {
        return tapdWorkspaceId != null && tapdIterationId != null;
    }

    public boolean anything() {
        return !issueRepositoryIds.isEmpty() || tapdPool();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
