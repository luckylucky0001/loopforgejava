package io.github.loopforge4j.server.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request bodies of the run API. */
final class ApiRequests {

    static final int MAX_INPUT = 200_000;
    static final int MAX_CONTEXT = 8_000;

    private ApiRequests() {}

    record StartRun(
            @NotBlank @Size(max = 200) String workflowId,
            @Size(max = MAX_INPUT) String input,
            @Size(max = 200) String projectId) {}

    record HumanContext(@Size(max = MAX_CONTEXT) String humanContext) {}

    record Retry(
            @Size(max = 200) String nodeId,
            @Size(max = 2_000) String reason,
            @Size(max = MAX_CONTEXT) String humanContext) {}

    record Reject(
            @Size(max = 200) String targetId,
            @NotBlank @Size(max = 2_000) String reason,
            @Size(max = MAX_CONTEXT) String humanContext) {}
}
