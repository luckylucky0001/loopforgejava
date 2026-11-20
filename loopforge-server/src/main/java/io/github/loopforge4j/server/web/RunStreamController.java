package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.engine.RunEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live run stream: {@code event} items (resumable via Last-Event-ID) and {@code run} snapshots. */
@Tag(name = "Runs")
@RestController
@RequestMapping("/api/v1/runs")
class RunStreamController {

    private final RunEngine engine;
    private final RunStreamBroadcaster broadcaster;

    RunStreamController(RunEngine engine, RunStreamBroadcaster broadcaster) {
        this.engine = engine;
        this.broadcaster = broadcaster;
    }

    @Operation(summary = "Server-sent events of one run; completes when the run is terminal")
    @GetMapping(path = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        engine.require(runId);
        return broadcaster.subscribe(runId, lastEventId != null ? lastEventId : after);
    }
}
