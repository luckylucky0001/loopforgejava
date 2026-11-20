package io.github.loopforge4j.server.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The public API as MCP tools (upstream {@code application.PublicOperations}). Sign-in, token and
 * password management, the event stream and bulk catalog import are intentionally not listed.
 */
public final class PlatformOperations {

    /**
     * @param readOnly the operation changes nothing; agents only ever see these
     */
    public record Operation(String name, String description, String method, String path, boolean readOnly) {

        private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

        public List<String> pathParameters() {
            List<String> names = new ArrayList<>();
            Matcher m = PARAM.matcher(path);
            while (m.find()) {
                names.add(m.group(1));
            }
            return names;
        }

        public boolean hasBody() {
            return "POST".equals(method) || "PUT".equals(method);
        }
    }

    private static final String RUNS = "/api/v1/runs";
    private static final String REQ = "/api/v1/projects/{projectId}/requirements";
    private static final String SCHED = "/api/v1/projects/{projectId}/schedules";
    private static final String KB = "/api/v1/knowledge";

    private static final List<Operation> ALL = List.of(
            read("health_get", "Check service health", "/actuator/health"),
            read("capabilities_get", "List the public API operations and MCP endpoints", "/api/v1/capabilities"),
            read("me_get", "Who the caller is and their project roles", "/api/v1/me"),
            // projects
            read("projects_list", "List the projects the caller can see", "/api/v1/projects"),
            read("projects_get", "Get one project", "/api/v1/projects/{id}"),
            write(
                    "projects_save",
                    "Create or update a project (administrators create, owners update)",
                    "PUT",
                    "/api/v1/projects/{id}"),
            write(
                    "projects_delete",
                    "Delete a project and its requirements, schedules and members",
                    "DELETE",
                    "/api/v1/projects/{id}"),
            read("project_members_list", "List project members and roles", "/api/v1/projects/{id}/members"),
            write(
                    "project_members_save",
                    "Add a member or change their role (viewer/developer/maintainer/owner)",
                    "PUT",
                    "/api/v1/projects/{id}/members/{userId}"),
            write(
                    "project_members_delete",
                    "Remove a project member",
                    "DELETE",
                    "/api/v1/projects/{id}/members/{userId}"),
            // repositories
            read(
                    "repositories_list",
                    "List code repositories (credentials are never returned)",
                    "/api/v1/repositories"),
            read("repositories_get", "Get one code repository", "/api/v1/repositories/{id}"),
            write("repositories_save", "Create or update a code repository", "PUT", "/api/v1/repositories/{id}"),
            write("repositories_delete", "Delete a code repository", "DELETE", "/api/v1/repositories/{id}"),
            // requirements
            read("requirements_list", "List a project's requirements. Query: status", REQ),
            write("requirements_create", "Create a requirement", "POST", REQ),
            read("requirements_get", "Get one requirement by number", REQ + "/{number}"),
            write("requirements_save", "Update a requirement", "PUT", REQ + "/{number}"),
            write("requirements_delete", "Delete a requirement", "DELETE", REQ + "/{number}"),
            write(
                    "requirements_dispatch",
                    "Start the requirement's SOP on its repositories. Body: {workflowId?, input?}",
                    "POST",
                    REQ + "/{number}/runs"),
            // schedules
            read("schedules_list", "List a project's scheduled runs", SCHED),
            write("schedules_create", "Create a scheduled run (cron, zone, workflowId, input)", "POST", SCHED),
            read("schedules_get", "Get one scheduled run", SCHED + "/{id}"),
            write("schedules_save", "Update a scheduled run", "PUT", SCHED + "/{id}"),
            write("schedules_run_now", "Start a scheduled run now", "POST", SCHED + "/{id}/run"),
            write("schedules_delete", "Delete a scheduled run", "DELETE", SCHED + "/{id}"),
            read(
                    "schedule_executions_list",
                    "Recent firings of a scheduled run with their run status. Query: limit",
                    SCHED + "/{id}/executions"),
            // notifications
            read("notifications_list", "List notification channels. Query: projectId", "/api/v1/notifications"),
            write("notifications_create", "Create a webhook notification channel", "POST", "/api/v1/notifications"),
            write("notifications_save", "Update a notification channel", "PUT", "/api/v1/notifications/{id}"),
            write("notifications_delete", "Delete a notification channel", "DELETE", "/api/v1/notifications/{id}"),
            // catalog
            read("workflows_list", "List SOP definitions", "/api/v1/workflows"),
            read("workflows_get", "Get one SOP definition", "/api/v1/workflows/{id}"),
            write("workflows_save", "Create or update an SOP definition", "PUT", "/api/v1/workflows/{id}"),
            write("workflows_delete", "Delete an SOP definition", "DELETE", "/api/v1/workflows/{id}"),
            read("workflow_revisions_list", "List an SOP's saved revisions", "/api/v1/workflows/{id}/revisions"),
            read(
                    "workflow_revisions_get",
                    "Get one saved revision of an SOP",
                    "/api/v1/workflows/{id}/revisions/{revision}"),
            write(
                    "workflow_revisions_restore",
                    "Restore an SOP revision as the newest one",
                    "POST",
                    "/api/v1/workflows/{id}/revisions/{revision}/restore"),
            write("workflows_publish", "Validate and publish an SOP", "POST", "/api/v1/workflows/{id}/publish"),
            write(
                    "workflows_clone",
                    "Copy an SOP as a new draft. Body: {id, name?, projectId?}",
                    "POST",
                    "/api/v1/workflows/{id}/clone"),
            write(
                    "workflows_validate",
                    "Validate an SOP definition without saving it",
                    "POST",
                    "/api/v1/workflows/validate"),
            read("agents_list", "List role (agent) definitions", "/api/v1/agents"),
            read("agents_get", "Get one role definition", "/api/v1/agents/{id}"),
            write("agents_save", "Create or update a role definition", "PUT", "/api/v1/agents/{id}"),
            write("agents_delete", "Delete a role definition", "DELETE", "/api/v1/agents/{id}"),
            read("agent_revisions_list", "List a role's saved revisions", "/api/v1/agents/{id}/revisions"),
            read("agent_revisions_get", "Get one saved revision of a role", "/api/v1/agents/{id}/revisions/{revision}"),
            read("rules_list", "List rule assets", "/api/v1/rules"),
            read("rules_get", "Get one rule asset", "/api/v1/rules/{id}"),
            write("rules_save", "Create or update a rule asset", "PUT", "/api/v1/rules/{id}"),
            write("rules_delete", "Delete a rule asset", "DELETE", "/api/v1/rules/{id}"),
            read("skills_list", "List skill assets", "/api/v1/skills"),
            read("skills_get", "Get one skill asset", "/api/v1/skills/{id}"),
            write("skills_save", "Create or update a skill asset", "PUT", "/api/v1/skills/{id}"),
            write("skills_delete", "Delete a skill asset", "DELETE", "/api/v1/skills/{id}"),
            read("mcp_servers_list", "List external MCP server assets", "/api/v1/mcp-servers"),
            read("mcp_servers_get", "Get one external MCP server asset", "/api/v1/mcp-servers/{id}"),
            write(
                    "mcp_servers_save",
                    "Create or update an external MCP server asset",
                    "PUT",
                    "/api/v1/mcp-servers/{id}"),
            write("mcp_servers_delete", "Delete an external MCP server asset", "DELETE", "/api/v1/mcp-servers/{id}"),
            read("executors_list", "List executor definitions (CLIs and models)", "/api/v1/executors"),
            // runs
            read("runs_list", "List runs. Query: status, workflowId, parentRunId, limit", RUNS),
            write("runs_start", "Start an SOP run. Body: {workflowId, input, projectId?, repositories?}", "POST", RUNS),
            read("runs_get", "Get one run with its node states", RUNS + "/{runId}"),
            read("runs_events", "Read a run's events. Query: after (sequence), limit", RUNS + "/{runId}/events"),
            read(
                    "runs_logs",
                    "Read a page of a run's logs. Query: nodeId, after or before (sequence), limit (max 200)",
                    RUNS + "/{runId}/logs"),
            read("runs_artifacts_list", "List a run's artifacts", RUNS + "/{runId}/artifacts"),
            read("runs_artifact_get", "Read one artifact", RUNS + "/{runId}/artifacts/{artifactId}"),
            read(
                    "runs_preview_get",
                    "Get a run's archived preview site and a signed link (valid for 2 hours) to open it",
                    RUNS + "/{runId}/preview"),
            read("runs_deliveries_list", "List a run's pull/merge requests", RUNS + "/{runId}/deliveries"),
            write(
                    "runs_deliveries_refresh",
                    "Refresh the state of a run's pull/merge requests",
                    "POST",
                    RUNS + "/{runId}/deliveries/refresh"),
            write("runs_cancel", "Cancel a run", "POST", RUNS + "/{runId}/cancel"),
            write("runs_pause", "Pause a run", "POST", RUNS + "/{runId}/pause"),
            write("runs_resume", "Resume a paused run", "POST", RUNS + "/{runId}/resume"),
            write("runs_retry", "Retry a failed run from its failed nodes", "POST", RUNS + "/{runId}/retry"),
            write(
                    "runs_node_approve",
                    "Approve a node waiting for a person",
                    "POST",
                    RUNS + "/{runId}/nodes/{nodeId}/approve"),
            write(
                    "runs_node_reject",
                    "Reject a node waiting for a person (send it back with feedback)",
                    "POST",
                    RUNS + "/{runId}/nodes/{nodeId}/reject"),
            write(
                    "runs_node_pass",
                    "Pass a failed or blocked node manually",
                    "POST",
                    RUNS + "/{runId}/nodes/{nodeId}/pass"),
            write(
                    "runs_node_decline_retry",
                    "Decline further retries of a node",
                    "POST",
                    RUNS + "/{runId}/nodes/{nodeId}/decline-retry"),
            // analytics
            read(
                    "analytics_get",
                    "Usage and delivery analytics. Query: projectId, workflowId, from, to (ISO-8601), grain"
                            + " (day/week/month), zone",
                    "/api/v1/analytics"),
            read(
                    "workflow_metrics_get",
                    "Operating metrics per SOP. Query: projectId, from, to",
                    "/api/v1/workflow-metrics"),
            // knowledge base
            read(
                    "knowledge_sources_list",
                    "List the knowledge base sources of a project. Query: projectId",
                    KB + "/sources"),
            read(
                    "knowledge_search",
                    "Search the team knowledge base. Query: projectId, q, sources (comma-separated)",
                    KB + "/search"),
            read(
                    "knowledge_document_get",
                    "Read a knowledge base document. Query: projectId, source, path, start, end",
                    KB + "/document"),
            read(
                    "knowledge_submissions_list",
                    "List distilled knowledge waiting for review. Query: projectId, status",
                    KB + "/submissions"),
            write(
                    "knowledge_submissions_create",
                    "Queue knowledge for review, from a run artifact ({runId, artifactId}) or raw content",
                    "POST",
                    KB + "/submissions"));

    private static final Map<String, Operation> BY_NAME;

    static {
        Map<String, Operation> map = new LinkedHashMap<>();
        for (Operation op : ALL) {
            if (map.put(op.name(), op) != null) {
                throw new IllegalStateException("duplicate operation " + op.name());
            }
        }
        BY_NAME = Map.copyOf(map);
    }

    private PlatformOperations() {}

    public static List<Operation> all() {
        return ALL;
    }

    public static Optional<Operation> find(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    private static Operation read(String name, String description, String path) {
        return new Operation(name, description, "GET", path, true);
    }

    private static Operation write(String name, String description, String method, String path) {
        return new Operation(name, description, method, path, false);
    }
}
