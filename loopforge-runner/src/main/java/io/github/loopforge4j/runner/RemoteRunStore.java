package io.github.loopforge4j.runner;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.store.RunChange;
import io.github.loopforge4j.core.store.RunMutator;
import io.github.loopforge4j.core.store.RunQuery;
import io.github.loopforge4j.core.store.RunStore;
import io.github.loopforge4j.core.store.StaleRunException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * The server's run store seen from an execution machine: only the runs assigned to it. Updates are
 * optimistic like the database store: read the run and its revision, apply the mutation locally,
 * commit if nobody changed it meanwhile (a person approving on the server, say), else retry.
 */
public class RemoteRunStore implements RunStore {

    private static final int MAX_ATTEMPTS = 8;

    private final RunnerClient client;
    private final Clock clock;

    public RemoteRunStore(RunnerClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public void insert(Run run, List<RunEvent> initialEvents) {
        client.post("/runs", Map.of("run", run, "events", initialEvents));
    }

    @Override
    public Optional<Run> find(String runId) {
        return client.find("/runs/" + encode(runId)).map(node -> Json.mapper().convertValue(node, Run.class));
    }

    @Override
    public List<Run> list(RunQuery query) {
        String statuses = query.statuses().stream()
                .map(RunStatus::wire)
                .map(s -> "status=" + s)
                .collect(Collectors.joining("&"));
        JsonNode node = client.get("/runs?limit=" + query.limit() + (statuses.isEmpty() ? "" : "&" + statuses));
        List<Run> runs = Json.mapper().convertValue(node, new TypeReference<List<Run>>() {});
        return runs.stream()
                .filter(r -> query.workflowId() == null || query.workflowId().equals(r.workflowId()))
                .filter(r -> query.parentRunId() == null
                        || query.parentRunId().equals(r.context().parentRunId()))
                .toList();
    }

    @Override
    public Optional<Run> update(String runId, RunMutator mutator) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Optional<JsonNode> stored = client.find("/runs/" + encode(runId));
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            Run run = Json.mapper().convertValue(stored.get(), Run.class);
            long expected = run.revision();
            RunChange change = new RunChange(run, clock, () -> artifacts(runId));
            if (!mutator.apply(change)) {
                return Optional.of(Json.mapper().convertValue(stored.get(), Run.class));
            }
            JsonNode reply = client.post(
                    "/runs/" + encode(runId) + "/commit",
                    Map.of(
                            "expectedRevision",
                            expected,
                            "run",
                            run,
                            "events",
                            change.pendingEvents(),
                            "artifacts",
                            change.pendingArtifacts()));
            if (reply.path("committed").asBoolean(false)) {
                return Optional.of(Json.mapper().convertValue(reply.get("run"), Run.class));
            }
        }
        throw new StaleRunException(runId, -1);
    }

    @Override
    public List<RunEvent> appendEvents(String runId, List<RunEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        return Json.mapper()
                .convertValue(
                        client.post("/runs/" + encode(runId) + "/events", Map.of("events", events)),
                        new TypeReference<List<RunEvent>>() {});
    }

    @Override
    public List<RunEvent> events(String runId, long afterSequence, int limit) {
        return Json.mapper()
                .convertValue(
                        client.get("/runs/" + encode(runId) + "/events?after=" + afterSequence + "&limit=" + limit),
                        new TypeReference<List<RunEvent>>() {});
    }

    @Override
    public List<Artifact> artifacts(String runId) {
        return Json.mapper()
                .convertValue(
                        client.get("/runs/" + encode(runId) + "/artifacts"), new TypeReference<List<Artifact>>() {});
    }

    @Override
    public Optional<Artifact> artifact(String runId, String artifactId) {
        return client.find("/runs/" + encode(runId) + "/artifacts/" + encode(artifactId))
                .map(node -> Json.mapper().convertValue(node, Artifact.class));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
