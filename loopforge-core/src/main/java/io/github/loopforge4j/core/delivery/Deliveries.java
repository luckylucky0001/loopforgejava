package io.github.loopforge4j.core.delivery;

import io.github.loopforge4j.core.domain.PullRequest;
import java.util.ArrayList;
import java.util.List;

/** Pull-request coverage rules (port of upstream {@code mrCoverageProblem}). */
public final class Deliveries {

    private Deliveries() {}

    /** {@code null} when one of the PRs carries {@code tip}; else what is wrong. */
    public static String coverageProblem(List<PullRequest> pulls, String tip) {
        List<String> stale = new ArrayList<>();
        List<String> merged = new ArrayList<>();
        int closed = 0;
        for (PullRequest pr : pulls) {
            String head = pr.headSha() == null ? "" : pr.headSha().strip();
            if (pr.open()) {
                if (head.isEmpty() || head.equals(tip)) {
                    return null;
                }
                stale.add(pr.url() + " 的源提交是 " + head);
            } else if (pr.merged()) {
                if (head.equals(tip)) {
                    return null;
                }
                merged.add(pr.url());
            } else {
                closed++;
            }
        }
        if (!stale.isEmpty()) {
            return "的 PR 还没有包含远端工作分支的 " + tip + "：" + String.join("，", stale);
        }
        if (!merged.isEmpty()) {
            return "只有已合并的 PR " + String.join("，", merged) + "，不包含本轮推送的 " + tip + "，需要创建新的 PR";
        }
        if (closed > 0) {
            return "的 PR 已关闭，需要重新打开或创建新的 PR";
        }
        return "在托管平台上没有 PR，需要创建";
    }

    /** The PR that best represents a delivery: an open one, else the most recently merged one. */
    public static PullRequest representative(List<PullRequest> pulls) {
        PullRequest best = null;
        for (PullRequest pr : pulls) {
            if (pr.open()) {
                return pr;
            }
            if (pr.merged() && (best == null || !best.merged())) {
                best = pr;
            } else if (best == null) {
                best = pr;
            }
        }
        return best;
    }
}
