package io.github.loopforge4j.server.browser;

import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.assistant.AssistantService;
import io.swagger.v3.oas.annotations.Operation;
import java.security.Principal;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The agent browser of a run or conversation, as the console shows and drives it (upstream
 * {@code POST /api/runs/{id}/browser}): the same browser the agents use, watched and taken over by
 * people who may operate the run or take part in the conversation.
 */
@RestController
public class BrowserController {

    private final ObjectProvider<RunEngine> engines;
    private final AccessPolicy access;
    private final AssistantService assistants;
    private final BrowserWorkspaces places;
    private final BrowserOperations operations;

    public BrowserController(
            ObjectProvider<RunEngine> engines,
            AccessPolicy access,
            AssistantService assistants,
            BrowserWorkspaces places,
            BrowserOperations operations) {
        this.engines = engines;
        this.access = access;
        this.assistants = assistants;
        this.places = places;
        this.operations = operations;
    }

    private BrowserWorkspaces.Place runPlace(String runId, Principal principal) {
        Run run = engines.getObject().require(runId);
        access.requireReadRun(principal, run);
        // driving the browser acts with the run's network: operators only
        access.requireOperateRun(principal, run);
        return places.run(run);
    }

    @Operation(
            summary = "Operate the run's agent browser: status, navigate, back/forward/reload, snapshot, screenshot,"
                    + " click/type/press by ref, tabs, console input events")
    @PostMapping("/api/v1/runs/{runId}/browser")
    Map<String, Object> run(
            @PathVariable String runId, @RequestBody(required = false) BrowserRequest body, Principal principal) {
        BrowserWorkspaces.Place place = runPlace(runId, principal);
        return operations
                .apply(
                        place.key(),
                        place.workspace(),
                        place.notHere(),
                        body == null ? BrowserRequest.of("status") : body)
                .view();
    }

    @Operation(summary = "Close the run's agent browser")
    @DeleteMapping("/api/v1/runs/{runId}/browser")
    Map<String, Object> closeRun(@PathVariable String runId, Principal principal) {
        BrowserWorkspaces.Place place = runPlace(runId, principal);
        return operations
                .apply(place.key(), null, null, BrowserRequest.of("close"))
                .view();
    }

    @Operation(summary = "Operate the conversation's agent browser (the one the assistant uses)")
    @PostMapping("/api/v1/assistant/sessions/{id}/browser")
    Map<String, Object> conversation(
            @PathVariable String id, @RequestBody(required = false) BrowserRequest body, Principal principal) {
        BrowserWorkspaces.Place place = places.conversation(assistants.participant(id, principal));
        return operations
                .apply(
                        place.key(),
                        place.workspace(),
                        place.notHere(),
                        body == null ? BrowserRequest.of("status") : body)
                .view();
    }

    @Operation(summary = "Close the conversation's agent browser")
    @DeleteMapping("/api/v1/assistant/sessions/{id}/browser")
    Map<String, Object> closeConversation(@PathVariable String id, Principal principal) {
        BrowserWorkspaces.Place place = places.conversation(assistants.participant(id, principal));
        return operations
                .apply(place.key(), null, null, BrowserRequest.of("close"))
                .view();
    }
}
