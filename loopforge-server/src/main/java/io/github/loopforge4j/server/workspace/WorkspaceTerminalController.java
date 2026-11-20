package io.github.loopforge4j.server.workspace;

import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.runtime.WorkspaceTerminals;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.assistant.AssistantService;
import io.github.loopforge4j.server.assistant.AssistantSession;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.security.Principal;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A shell in a run's or conversation's workspace (upstream workspace terminal): it runs through the
 * workspace's own sandbox, opens only while no agent works there, belongs to the person who opened
 * it, and closes when its page stops polling.
 */
@RestController
public class WorkspaceTerminalController {

    /**
     * One terminal operation.
     *
     * @param operation {@code open}, {@code read}, {@code input} or {@code close}
     * @param data typed input (for {@code input})
     */
    public record TerminalRequest(String operation, String id, String data, Integer cols, Integer rows) {}

    private final ObjectProvider<RunEngine> engines;
    private final AccessPolicy access;
    private final AssistantService assistants;
    private final WorkspaceTerminals terminals;

    public WorkspaceTerminalController(
            ObjectProvider<RunEngine> engines, AccessPolicy access, AssistantService assistants, Clock clock) {
        this.engines = engines;
        this.access = access;
        this.assistants = assistants;
        this.terminals = new WorkspaceTerminals(clock);
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String, Object>> refused(IOException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of(
                        "type",
                        "about:blank",
                        "title",
                        "Conflict",
                        "status",
                        409,
                        "detail",
                        e.getMessage() == null ? "terminal failed" : e.getMessage()));
    }

    /** Agents at work and a person in a shell must not edit the same workspace at once. */
    static Optional<String> busy(Run run) {
        if (run.status() == RunStatus.QUEUED) {
            return Optional.of("运行还在排队，工作区尚未就绪");
        }
        if (run.cancelRequested()) {
            return Optional.of("运行正在取消");
        }
        if (run.nodeRuns().stream().anyMatch(n -> n.status() == NodeStatus.RUNNING)) {
            return Optional.of("有节点正在运行，暂停运行或等它停在审批处再打开终端");
        }
        return Optional.empty();
    }

    @Operation(
            summary = "A shell in the run's workspace (open / read / input / close); only while no node runs,"
                    + " for people who may operate the run")
    @PostMapping("/api/v1/runs/{runId}/workspace/terminal")
    Map<String, Object> run(@PathVariable String runId, @RequestBody TerminalRequest body, Principal principal)
            throws IOException {
        RunEngine engine = engines.getObject();
        Run run = engine.require(runId);
        access.requireReadRun(principal, run);
        access.requireOperateRun(principal, run);
        CurrentUser user = access.current(principal);
        String scope = "run:" + run.workspaceOwnerId() + ":" + user.id();
        if ("open".equals(op(body))) {
            if (engine.workspace(runId).shared()) {
                throw new IOException("子 SOP 共享父任务工作区，请从父任务打开终端");
            }
            Optional<String> busy = busy(run);
            if (busy.isPresent()) {
                throw new IOException(busy.get());
            }
            Sandbox sandbox = engine.existingWorkspaceSandbox(runId);
            Map<String, Object> out = handle(sandbox, scope, body, Map.of("LOOPFORGE_RUN_ID", runId));
            engine.note(runId, "workspace.terminal", user.username() + " 打开了工作区终端，之后的检查仅供历史参考");
            return out;
        }
        return handle(null, scope, body, Map.of());
    }

    @Operation(summary = "A shell in the conversation's workspace; only while the assistant is not replying")
    @PostMapping("/api/v1/assistant/sessions/{id}/terminal")
    Map<String, Object> conversation(@PathVariable String id, @RequestBody TerminalRequest body, Principal principal)
            throws IOException {
        AssistantSession session = assistants.participant(id, principal);
        CurrentUser user = access.current(principal);
        String scope = "conv:" + session.id() + ":" + user.id();
        if ("open".equals(op(body))) {
            if (!"idle".equals(session.status())) {
                throw new IOException("助手正在回复，回复结束后再打开终端");
            }
            Sandbox sandbox = engines.getObject()
                    .conversations()
                    .sandbox(session.id(), session.sandboxProfileId())
                    .orElseThrow(() -> new IOException("沙箱 " + session.sandboxProfileId() + " 在本实例不可用"));
            return handle(sandbox, scope, body, Map.of("LOOPFORGE_CONVERSATION_ID", session.id()));
        }
        return handle(null, scope, body, Map.of());
    }

    private static String op(TerminalRequest body) {
        return body == null || body.operation() == null ? "" : body.operation();
    }

    private Map<String, Object> handle(
            Sandbox sandbox, String scope, TerminalRequest body, Map<String, String> environment) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (op(body)) {
            case "open" -> {
                WorkspaceTerminals.Result r = terminals.open(
                        sandbox,
                        scope,
                        body.cols() == null ? 100 : body.cols(),
                        body.rows() == null ? 30 : body.rows(),
                        environment);
                out.put("id", r.id());
                out.put("idleSeconds", WorkspaceTerminals.IDLE.toSeconds());
            }
            case "read" -> {
                WorkspaceTerminals.Result r = terminals.read(scope, body.id());
                out.put("id", r.id());
                out.put("data", Base64.getEncoder().encodeToString(r.data()));
                out.put("exited", r.exited());
            }
            case "input" -> {
                terminals.input(scope, body.id(), body.data());
                out.put("id", body.id());
            }
            case "close" -> {
                terminals.close(scope, body.id());
                out.put("id", body.id());
                out.put("exited", true);
            }
            default -> throw new IOException("未知终端操作");
        }
        return out;
    }

    @Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT5S")
    void reap() {
        terminals.reap();
    }

    @PreDestroy
    void closeAll() {
        terminals.close();
    }
}
