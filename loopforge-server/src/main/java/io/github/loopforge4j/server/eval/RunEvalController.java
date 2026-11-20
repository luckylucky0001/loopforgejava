package io.github.loopforge4j.server.eval;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Quality evaluations of settled runs. */
@Tag(name = "Runs")
@RestController
@RequestMapping("/api/v1/runs/{runId}")
class RunEvalController {

    private final RunEvalService evals;

    RunEvalController(RunEvalService evals) {
        this.evals = evals;
    }

    @Operation(summary = "Evaluate a settled run (AI, with your credentials); the result arrives on the stream")
    @PostMapping("/eval")
    ResponseEntity<RunEval> start(@PathVariable String runId, Principal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(evals.start(runId, principal));
    }

    @Operation(summary = "The newest evaluation ({} when there is none)")
    @GetMapping("/eval")
    Object latest(@PathVariable String runId, Principal principal) {
        RunEval latest = evals.latest(runId, principal);
        return latest == null ? Map.of() : latest;
    }

    @GetMapping("/evals")
    List<RunEval> list(@PathVariable String runId, Principal principal) {
        return evals.list(runId, principal);
    }

    @GetMapping(path = "/eval/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable String runId, Principal principal) {
        return evals.stream(runId, principal);
    }

    @Operation(summary = "Apply chosen recommendations to the current SOP or roles (a new revision each)")
    @PostMapping("/eval/apply")
    RunEval apply(@PathVariable String runId, @RequestBody RunEvalService.Apply body, Principal principal) {
        return evals.apply(runId, body, principal);
    }
}
