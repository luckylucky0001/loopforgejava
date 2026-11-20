package io.github.loopforge4j.server.browser;

import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.server.assistant.AssistantSession;
import io.github.loopforge4j.server.assistant.AssistantStore;
import io.github.loopforge4j.server.mcp.ServerPlatformMcp;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Which browser belongs to a run or conversation, the workspace whose files it may open, and why it
 * cannot run on this instance when it cannot.
 */
@Component
public class BrowserWorkspaces {

    /** A browser key, its workspace (when here) and the reason it is not here. */
    public record Place(String key, Path workspace, String notHere) {}

    private final ObjectProvider<RunEngine> engines;
    private final AssistantStore assistants;
    private final ServerPlatformMcp platform;

    public BrowserWorkspaces(ObjectProvider<RunEngine> engines, AssistantStore assistants, ServerPlatformMcp platform) {
        this.engines = engines;
        this.assistants = assistants;
        this.platform = platform;
    }

    public static String runKey(String runId) {
        return "run:" + runId;
    }

    public static String conversationKey(String sessionId) {
        return "conv:" + sessionId;
    }

    public Place run(Run run) {
        String key = runKey(run.workspaceOwnerId());
        String profile = run.workflow().sandboxProfileId();
        if (!platform.sharesHostNetwork(profile)) {
            return new Place(key, null, isolated(profile));
        }
        RunEngine.WorkspaceLocation location = engines.getObject().workspace(run.id());
        return location.available()
                ? new Place(key, location.root(), null)
                : new Place(key, null, "Agent 浏览器和工作区在同一台机器上运行：" + location.unavailable());
    }

    public Place conversation(AssistantSession session) {
        String key = conversationKey(session.id());
        String profile = session.sandboxProfileId();
        if (!platform.sharesHostNetwork(profile)) {
            return new Place(key, null, isolated(profile));
        }
        Optional<Path> workspace;
        try {
            workspace = engines.getObject().conversations().workspace(session.id(), profile);
        } catch (java.io.IOException | RuntimeException e) {
            workspace = Optional.empty();
        }
        return new Place(key, workspace.orElse(null), null);
    }

    public Optional<Place> conversation(String sessionId) {
        return assistants.find(sessionId).map(this::conversation);
    }

    static String isolated(String profile) {
        return "沙箱 " + profile + " 与服务器网络隔离：Agent 浏览器在服务器上运行，只提供给共享主机网络的沙箱";
    }
}
