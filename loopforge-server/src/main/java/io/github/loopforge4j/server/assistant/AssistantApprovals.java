package io.github.loopforge4j.server.assistant;

import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.mcp.InternalApi;
import io.github.loopforge4j.server.web.ApiException;
import java.io.IOException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The speaker's decisions on the writes the assistant proposed: confirming runs the very operation
 * shown on the card with the speaker's own credentials (and so only with their rights); discarding
 * drops it. Only the person whose turn proposed a write decides it.
 */
@Service
public class AssistantApprovals {

    private final AssistantService assistants;
    private final AssistantDrafts drafts;
    private final InternalApi api;
    private final AssistantStreams streams;
    private final AccessPolicy access;

    public AssistantApprovals(
            AssistantService assistants,
            AssistantDrafts drafts,
            InternalApi api,
            AssistantStreams streams,
            AccessPolicy access) {
        this.assistants = assistants;
        this.drafts = drafts;
        this.api = api;
        this.streams = streams;
        this.access = access;
    }

    public List<Map<String, Object>> list(String sessionId, Principal principal) {
        AssistantSession session = assistants.participant(sessionId, principal);
        String me = access.current(principal).id();
        return drafts.list(session.id()).stream().map(d -> view(d, me)).toList();
    }

    static Map<String, Object> view(AssistantDrafts.Draft d, String me) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", d.id());
        out.put("tool", d.tool());
        out.put("method", d.method());
        out.put("target", d.target());
        out.put("body", d.body());
        out.put("status", d.status());
        out.put("turnId", d.turnId());
        out.put("mine", d.userId().equals(me));
        out.put("resultStatus", d.resultStatus());
        out.put("resultText", d.resultText());
        out.put("decidedBy", d.decidedBy());
        out.put("createdAt", d.createdAt());
        out.put("decidedAt", d.decidedAt());
        out.values().removeIf(java.util.Objects::isNull);
        return out;
    }

    /**
     * @param authorization the speaker's own credentials, forwarded to the operation
     */
    public Map<String, Object> decide(
            String sessionId, String draftId, boolean confirm, Principal principal, String authorization) {
        AssistantSession session = assistants.participant(sessionId, principal);
        CurrentUser user = access.current(principal);
        AssistantDrafts.Draft draft = drafts.find(session.id(), draftId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "draft not found"));
        if (!draft.userId().equals(user.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有提出这项请求的人能确认或放弃它");
        }
        if (!drafts.claim(draft.id(), user.username())) {
            throw new ApiException(HttpStatus.CONFLICT, "这项操作已经处理过了");
        }
        if (!confirm) {
            drafts.finish(draft.id(), AssistantDrafts.DISCARDED, null, null);
        } else {
            try {
                InternalApi.Response response = api.call(draft.method(), draft.target(), draft.body(), authorization);
                drafts.finish(
                        draft.id(),
                        response.ok() ? AssistantDrafts.APPLIED : AssistantDrafts.FAILED,
                        response.status(),
                        response.body());
            } catch (IOException e) {
                drafts.finish(draft.id(), AssistantDrafts.FAILED, null, "平台接口不可用");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                drafts.finish(draft.id(), AssistantDrafts.FAILED, null, "interrupted");
            }
        }
        AssistantDrafts.Draft decided = drafts.find(session.id(), draft.id()).orElseThrow();
        streams.publish(session.id(), "drafts", Map.of("id", decided.id(), "status", decided.status()));
        return view(decided, user.id());
    }

    public String mode(Principal principal) {
        return drafts.mode(access.current(principal).id());
    }

    public void mode(Principal principal, String mode) {
        CurrentUser user = access.current(principal);
        if (user.id() == null || user.agent() != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "preferences need an account");
        }
        drafts.mode(user.id(), mode);
    }
}
