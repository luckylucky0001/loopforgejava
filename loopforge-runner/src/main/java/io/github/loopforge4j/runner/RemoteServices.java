package io.github.loopforge4j.runner;

import io.github.loopforge4j.core.delivery.DeliveryException;
import io.github.loopforge4j.core.delivery.DeliveryProvider;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.mcp.PlatformMcp;
import io.github.loopforge4j.core.store.StoreException;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/** Engine ports the server answers for the machine: platform MCP bindings and pull request lookups. */
final class RemoteServices {

    private RemoteServices() {}

    /** Platform and knowledge MCP servers, addressed at the server URL the machine uses. */
    static PlatformMcp platformMcp(RunnerClient client) {
        return invocation -> {
            try {
                JsonNode reply = client.post(
                        "/runs/" + RemoteRunStore.encode(invocation.runId()) + "/platform-mcp",
                        Map.of(
                                "nodeId", invocation.nodeId(),
                                "agentId", invocation.agentId(),
                                "platform", invocation.platform(),
                                "knowledge", invocation.knowledge()));
                return Json.mapper().convertValue(reply, PlatformMcp.Binding.class);
            } catch (StoreException e) {
                return new PlatformMcp.Binding(List.of(), List.of(), List.of("平台 MCP 不可用：" + e.getMessage()));
            }
        };
    }

    /**
     * Pull requests checked by the server with its API credentials; the machine needs no tracker
     * token, only Git credentials to push.
     */
    static DeliveryProvider delivery(RunnerClient client) {
        return new DeliveryProvider() {
            @Override
            public boolean supports(RepositoryContext repository) {
                return true;
            }

            @Override
            public List<PullRequest> pullRequests(RepositoryContext repository, String head, String base)
                    throws DeliveryException {
                try {
                    JsonNode reply = client.post(
                            "/deliveries",
                            Map.of("repositoryId", repository.repositoryId(), "head", head, "base", base));
                    if (!reply.path("supported").asBoolean(false)) {
                        return List.of();
                    }
                    return Json.mapper()
                            .convertValue(reply.get("pullRequests"), new TypeReference<List<PullRequest>>() {});
                } catch (StoreException e) {
                    throw new DeliveryException("server cannot look up pull requests: " + e.getMessage());
                }
            }
        };
    }
}
