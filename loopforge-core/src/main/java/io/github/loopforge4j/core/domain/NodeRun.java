package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import java.time.Instant;

/**
 * Mutable execution state of one node. Only mutated inside a store transaction (see {@code
 * RunStore#update}); readers always get a private copy.
 */
@JsonAutoDetect(
        fieldVisibility = Visibility.ANY,
        getterVisibility = Visibility.NONE,
        isGetterVisibility = Visibility.NONE,
        setterVisibility = Visibility.NONE)
public final class NodeRun {

    private String nodeId;
    private NodeStatus status = NodeStatus.PENDING;
    private int attempt;
    private int reworkCount;
    private String output;
    private String error;
    private String provider;
    private String model;
    private String sessionId;
    private String resumeSessionId;
    private String activity;
    private String feedback;
    private TokenUsage usage = TokenUsage.ZERO;
    private int toolCalls;
    private GateResult gateResult;
    private String childRunId;
    private Instant startedAt;
    private Instant finishedAt;
    /** Parked by an operator pause; only {@code resume} continues it (upstream matched activity text). */
    private boolean paused;
    /** Stopped by an engine restart mid-attempt; the next start continues the same attempt. */
    private boolean interrupted;
    /** Attempt budget granted by a manual retry beyond the node's {@code maxAttempts}. */
    private Integer maxAttemptsOverride;

    NodeRun() {
        // for Jackson
    }

    public static NodeRun pending(String nodeId) {
        NodeRun run = new NodeRun();
        run.nodeId = nodeId;
        return run;
    }

    /** Clears execution results so the node is scheduled again (rework, retry, resume). */
    public void resetToPending() {
        status = NodeStatus.PENDING;
        error = null;
        output = null;
        childRunId = null;
        finishedAt = null;
        gateResult = null;
        paused = false;
        interrupted = false;
    }

    public void finish(NodeStatus newStatus, Instant at) {
        status = newStatus;
        finishedAt = at;
    }

    public void addUsage(TokenUsage delta, int tools) {
        usage = usage == null ? delta : usage.plus(delta);
        toolCalls += tools;
    }

    public String nodeId() {
        return nodeId;
    }

    public NodeStatus status() {
        return status;
    }

    public void status(NodeStatus value) {
        this.status = value;
    }

    public int attempt() {
        return attempt;
    }

    public void attempt(int value) {
        this.attempt = value;
    }

    public int reworkCount() {
        return reworkCount;
    }

    public void reworkCount(int value) {
        this.reworkCount = value;
    }

    public String output() {
        return output;
    }

    public void output(String value) {
        this.output = value;
    }

    public String error() {
        return error;
    }

    public void error(String value) {
        this.error = value;
    }

    public String provider() {
        return provider;
    }

    public void provider(String value) {
        this.provider = value;
    }

    public String model() {
        return model;
    }

    public void model(String value) {
        this.model = value;
    }

    public String sessionId() {
        return sessionId;
    }

    public void sessionId(String value) {
        this.sessionId = value;
    }

    public String resumeSessionId() {
        return resumeSessionId;
    }

    public void resumeSessionId(String value) {
        this.resumeSessionId = value;
    }

    public String activity() {
        return activity;
    }

    public void activity(String value) {
        this.activity = value;
    }

    public String feedback() {
        return feedback;
    }

    public void feedback(String value) {
        this.feedback = value;
    }

    public TokenUsage usage() {
        return usage;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public GateResult gateResult() {
        return gateResult;
    }

    public void gateResult(GateResult value) {
        this.gateResult = value;
    }

    public String childRunId() {
        return childRunId;
    }

    public void childRunId(String value) {
        this.childRunId = value;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public void startedAt(Instant value) {
        this.startedAt = value;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public boolean paused() {
        return paused;
    }

    public void paused(boolean value) {
        this.paused = value;
    }

    public boolean interrupted() {
        return interrupted;
    }

    public void interrupted(boolean value) {
        this.interrupted = value;
    }

    public Integer maxAttemptsOverride() {
        return maxAttemptsOverride;
    }

    public void maxAttemptsOverride(Integer value) {
        this.maxAttemptsOverride = value;
    }

    @Override
    public String toString() {
        return "NodeRun[" + nodeId + " " + status + " attempt=" + attempt + " reworks=" + reworkCount + "]";
    }
}
