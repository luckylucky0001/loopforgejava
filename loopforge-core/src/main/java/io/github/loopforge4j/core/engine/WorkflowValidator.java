package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.ArtifactContract;
import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.VerificationGate;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowEdge;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural validation of an SOP (port of upstream {@code validate}/{@code validateNode}/{@code
 * validateDAG}). Unlike upstream it reports every problem at once so the designer can show them
 * together.
 */
public final class WorkflowValidator {

    /**
     * Identifiers of workflows, nodes and agents end up in URLs, file names and database columns
     * (VARCHAR(200)), so they are restricted to a portable character set.
     */
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}");

    public static final int MAX_NAME = 500;
    public static final int MAX_NODES = 200;
    public static final int MAX_EDGES = 2_000;
    public static final int MAX_TEXT = 200_000;
    public static final int MAX_ARTIFACT_NAME = 300;
    public static final int MAX_ARTIFACT_PATH = 1_000;
    public static final int MAX_MEDIA_TYPE = 200;

    private WorkflowValidator() {}

    /**
     * Validates a repository definition. Local paths and {@code file:} URLs let a run read anything the
     * server can read, and plain {@code http://} leaks credentials, so both need explicit opt-in.
     */
    public static List<String> repositoryProblems(
            io.github.loopforge4j.core.domain.RepositoryDefinition repository, boolean allowLocalRemotes) {
        List<String> problems = new ArrayList<>();
        checkId("repository", repository.id(), problems);
        checkLength("repository name", repository.name(), MAX_NAME, problems);
        String url = repository.cloneUrl();
        if (url.isBlank()) {
            problems.add("repository cloneUrl is required");
        } else if (url.length() > 2_000 || url.chars().anyMatch(c -> c < 0x21) || url.startsWith("-")) {
            problems.add("repository cloneUrl is invalid");
        } else {
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            java.util.regex.Matcher userInfo = java.util.regex.Pattern.compile("^[a-z][a-z0-9+.-]*://([^/@]*)@")
                    .matcher(lower);
            if (userInfo.find()
                    && (lower.startsWith("http") || userInfo.group(1).contains(":"))) {
                problems.add("credentials must not be embedded in the clone URL; configure loopforge.git instead");
            }
            boolean local = lower.startsWith("file:") || url.startsWith("/") || url.startsWith(".");
            boolean remote = lower.startsWith("https://")
                    || lower.startsWith("ssh://")
                    || url.matches("^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:[^:].*");
            if (local && !allowLocalRemotes) {
                problems.add("local repository paths are disabled (loopforge.git.allow-local-remotes)");
            } else if (lower.startsWith("http://") && !allowLocalRemotes) {
                problems.add("plain http:// remotes would expose credentials; use https://");
            } else if (!local && !remote && !lower.startsWith("http://")) {
                problems.add("repository cloneUrl must be https://, ssh://, user@host:path or (if enabled) file:");
            }
        }
        if (!io.github.loopforge4j.core.runtime.git.GitWorkspace.validBranch(repository.defaultBranch())) {
            problems.add("repository defaultBranch is invalid");
        }
        if (repository.githubRepo() != null && !repository.githubRepo().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            problems.add("githubRepo must be owner/name");
        }
        return problems;
    }

    /** Throws when the role definition is invalid. */
    public static void validateAgent(AgentDefinition agent) {
        List<String> problems = agentProblems(agent);
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
    }

    public static List<String> agentProblems(AgentDefinition agent) {
        List<String> problems = new ArrayList<>();
        checkId("agent", agent.id(), problems);
        checkLength("agent " + clip(agent.id()) + " name", agent.name(), MAX_NAME, problems);
        checkLength("agent " + clip(agent.id()) + " prompt", agent.prompt(), MAX_TEXT, problems);
        if (agent.executorId() != null && !agent.executorId().isBlank()) {
            checkId("executor", agent.executorId(), problems);
        }
        if (agent.loop().maxIterations() > 20) {
            problems.add("agent " + clip(agent.id()) + " loop maxIterations must be at most 20");
        }
        validateContracts("agent " + clip(agent.id()), agent.artifactOutputs(), problems);
        for (VerificationGate gate : agent.verification()) {
            if (gate.name() == null || gate.name().isBlank()) {
                problems.add("agent " + clip(agent.id()) + " has a verification gate without id or name");
            }
            checkLength("agent " + clip(agent.id()) + " gate", gate.script(), MAX_TEXT, problems);
        }
        String who = "agent " + clip(agent.id());
        if (agent.rules().size() > 50
                || agent.skills().size() > 50
                || agent.mcpServers().size() > 50) {
            problems.add(who + " may bind at most 50 rules, skills and MCP servers each");
        }
        agent.rules().forEach(r -> checkLength(who + " rule", r, MAX_TEXT, problems));
        agent.skills().forEach(id -> checkId("skill", id, problems));
        agent.mcpServers().forEach(id -> checkId("MCP server", id, problems));
        agent.mcpServers().stream()
                .filter(io.github.loopforge4j.core.mcp.PlatformMcp::reserved)
                .distinct()
                .forEach(id -> problems.add(
                        who + ": MCP server id " + id + " is reserved (use platformMcp / knowledgeBase on the role)"));
        java.util.Set<String> hookIds = new java.util.HashSet<>();
        if (agent.hooks().size() > 20) {
            problems.add(who + " may define at most 20 hooks");
        }
        for (io.github.loopforge4j.core.domain.AgentHook hook : agent.hooks()) {
            checkId("hook", hook.id(), problems);
            if (!hookIds.add(hook.id())) {
                problems.add(who + " hook id " + clip(hook.id()) + " is duplicated");
            }
            if (!io.github.loopforge4j.core.domain.AgentHook.EVENTS.contains(hook.event())) {
                problems.add(who + " hook " + clip(hook.id()) + " event must be one of "
                        + new java.util.TreeSet<>(io.github.loopforge4j.core.domain.AgentHook.EVENTS));
            }
            if (hook.script().isBlank()) {
                problems.add(who + " hook " + clip(hook.id()) + " needs a script");
            }
            checkLength(who + " hook script", hook.script(), MAX_TEXT, problems);
            checkLength(who + " hook matcher", hook.matcher(), MAX_NAME, problems);
        }
        return problems;
    }

    /** Validates a rule, skill or MCP server definition. */
    public static List<String> assetProblems(io.github.loopforge4j.core.domain.CatalogAsset asset) {
        List<String> problems = new ArrayList<>();
        String kind = switch (asset) {
            case io.github.loopforge4j.core.domain.RuleDefinition r -> "rule";
            case io.github.loopforge4j.core.domain.SkillDefinition s -> "skill";
            case io.github.loopforge4j.core.domain.McpServerDefinition m -> "MCP server";
            default -> "asset";
        };
        checkId(kind, asset.id(), problems);
        checkLength(kind + " name", asset.name(), MAX_NAME, problems);
        switch (asset) {
            case io.github.loopforge4j.core.domain.RuleDefinition rule -> {
                if (rule.content().isBlank()) {
                    problems.add("rule content is required");
                }
                checkLength("rule content", rule.content(), MAX_TEXT, problems);
            }
            case io.github.loopforge4j.core.domain.SkillDefinition skill -> {
                if (skill.content().isBlank() && !skill.files().containsKey("SKILL.md")) {
                    problems.add("skill content (SKILL.md) is required");
                }
                if (skill.files().size() > 200) {
                    problems.add("skill may have at most 200 files");
                }
                long size = skill.content().length();
                for (Map.Entry<String, String> file : skill.files().entrySet()) {
                    size += file.getValue() == null ? 0 : file.getValue().length();
                    try {
                        if (!file.getKey().equals("SKILL.md")) {
                            io.github.loopforge4j.core.runtime.materialize.AgentMaterializer.checkSkillPath(
                                    file.getKey());
                        }
                    } catch (IllegalArgumentException e) {
                        problems.add(e.getMessage());
                    }
                }
                if (size > 2_000_000) {
                    problems.add("skill files may total at most 2 MB");
                }
            }
            case io.github.loopforge4j.core.domain.McpServerDefinition server -> {
                if (io.github.loopforge4j.core.mcp.PlatformMcp.reserved(server.id())) {
                    problems.add("MCP server id " + server.id() + " is reserved");
                }
                if (!server.transport().equals("stdio") && !server.transport().equals("http")) {
                    problems.add("MCP server transport must be stdio or http");
                } else if (server.http()) {
                    String url = server.url() == null ? "" : server.url().strip();
                    if (!url.startsWith("https://") && !url.startsWith("http://")) {
                        problems.add("http MCP server needs an http(s) url");
                    }
                    if (url.contains("${secret:")) {
                        problems.add("MCP server " + server.id() + ": secrets belong in headers, not in the URL");
                    }
                } else if (server.command() == null || server.command().isBlank()) {
                    problems.add("stdio MCP server needs a command");
                }
                for (String key : server.environment().keySet()) {
                    if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,99}")) {
                        problems.add("MCP server environment variable name is invalid: " + clip(key));
                    }
                }
                for (Map.Entry<String, String> e : server.environment().entrySet()) {
                    if (looksLikeSecret(e.getKey())
                            && e.getValue() != null
                            && !e.getValue().contains("${secret:")) {
                        problems.add("MCP server " + server.id() + ": put the value of " + e.getKey()
                                + " in server configuration and reference it as ${secret:NAME}");
                    }
                }
                for (Map.Entry<String, String> h : server.headers().entrySet()) {
                    if (h.getKey().equalsIgnoreCase("authorization")
                            && !h.getValue().contains("${secret:")) {
                        problems.add(
                                "MCP server " + server.id() + ": reference the Authorization value as ${secret:NAME}");
                    }
                }
                if (server.timeoutSec() < 0 || server.timeoutSec() > 3600) {
                    problems.add("MCP server timeoutSec must be between 0 and 3600");
                }
            }
            default -> {}
        }
        return problems;
    }

    private static boolean looksLikeSecret(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("TOKEN")
                || upper.contains("SECRET")
                || upper.contains("PASSWORD")
                || upper.endsWith("KEY");
    }

    public static void validate(WorkflowDefinition workflow) {
        List<String> problems = problems(workflow);
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
    }

    public static List<String> problems(WorkflowDefinition workflow) {
        List<String> problems = new ArrayList<>();
        checkId("workflow", workflow.id(), problems);
        checkLength("workflow name", workflow.name(), MAX_NAME, problems);
        if (workflow.nodes().size() > MAX_NODES || workflow.edges().size() > MAX_EDGES) {
            problems.add("workflow may have at most " + MAX_NODES + " nodes and " + MAX_EDGES + " edges");
            return problems;
        }
        if (workflow.sandboxProfileId() == null || workflow.sandboxProfileId().isBlank()) {
            problems.add("workflow sandbox profile is required");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (WorkflowNode node : workflow.nodes()) {
            if (node.id().isBlank()) {
                problems.add("node id is required");
                continue;
            }
            if (!ID.matcher(node.id()).matches()) {
                checkId("node", node.id(), problems);
                continue;
            }
            if (!ids.add(node.id())) {
                problems.add("duplicate node " + node.id());
            }
            validateNode(workflow.id(), node, problems);
        }
        for (WorkflowNode node : workflow.nodes()) {
            if (node.rejectTo() != null && !ids.contains(node.rejectTo())) {
                problems.add("node " + node.id() + " reject target '" + node.rejectTo() + "' not found");
            }
            if (node.onFailure() == FailurePolicy.REJECT && node.rejectTo() == null) {
                problems.add("node " + node.id() + " has onFailure=reject but no rejectTo target");
            }
        }
        validateDag(ids, workflow.edges(), problems);
        return problems;
    }

    private static void validateNode(String workflowId, WorkflowNode node, List<String> problems) {
        String id = node.id();
        if (node.type() == null) {
            problems.add("node " + id + " type is required");
            return;
        }
        switch (node.type()) {
            case AGENT -> {
                if (blank(node.agentId())) {
                    problems.add("node " + id + " agentId is required");
                }
            }
            case APPROVAL -> {
                // approval nodes only need an optional task prompt
            }
            case SOP -> {
                if (blank(node.sopWorkflowId())) {
                    problems.add("node " + id + " sopWorkflowId is required");
                } else if (node.sopWorkflowId().trim().equals(workflowId)) {
                    problems.add("node " + id + " cannot reference its own workflow");
                }
            }
            case SCRIPT -> {
                if (blank(node.script())) {
                    problems.add("node " + id + " script is required");
                }
            }
        }
        if (node.timeoutSeconds() != null && node.timeoutSeconds() <= 0) {
            problems.add("node " + id + " timeoutSeconds must be positive");
        }
        if (node.maxAttempts() < 0 || node.maxReworks() < 0) {
            problems.add("node " + id + " attempt and rework budgets must not be negative");
        }
        checkLength("node " + id + " name", node.name(), MAX_NAME, problems);
        checkLength("node " + id + " script", node.script(), MAX_TEXT, problems);
        checkLength("node " + id + " taskPrompt", node.taskPrompt(), MAX_TEXT, problems);
        for (String reference : new String[] {node.agentId(), node.executorId(), node.sopWorkflowId()}) {
            if (reference != null
                    && !reference.isBlank()
                    && !ID.matcher(reference).matches()) {
                problems.add("node " + id + " references an invalid id '" + clip(reference) + "'");
            }
        }
        validateContracts("node " + id, node.artifactOutputs(), problems);
    }

    private static void validateContracts(String owner, List<ArtifactContract> contracts, List<String> problems) {
        Set<String> names = new HashSet<>();
        for (ArtifactContract contract : contracts) {
            if (contract.name().isBlank()
                    || contract.name().length() > MAX_ARTIFACT_NAME
                    || contract.path().length() > MAX_ARTIFACT_PATH
                    || contract.mediaType().length() > MAX_MEDIA_TYPE
                    || !safeRelativePath(contract.path())) {
                problems.add(owner + " has invalid artifact contract '" + clip(contract.name()) + "'");
            }
            if (!names.add(contract.name())) {
                problems.add(owner + " has duplicate artifact " + clip(contract.name()));
            }
        }
    }

    private static void checkId(String kind, String id, List<String> problems) {
        if (id == null || id.isBlank()) {
            problems.add(kind + " id is required");
        } else if (!ID.matcher(id).matches()) {
            problems.add(kind + " id '" + clip(id) + "' must start with a letter or digit, use only"
                    + " letters, digits, '.', '_', ':' or '-', and be at most 200 characters");
        }
    }

    private static void checkLength(String what, String value, int max, List<String> problems) {
        if (value != null && value.length() > max) {
            problems.add(what + " must be at most " + max + " characters");
        }
    }

    private static String clip(String value) {
        return value == null ? "" : value.length() <= 60 ? value : value.substring(0, 60) + "…";
    }

    /** Relative, normalized path that stays inside the workspace. */
    public static boolean safeRelativePath(String path) {
        if (blank(path)) {
            return false;
        }
        try {
            Path candidate = Path.of(path).normalize();
            String text = candidate.toString();
            return !candidate.isAbsolute()
                    && !text.isEmpty()
                    && !text.equals(".")
                    && !text.startsWith("..")
                    && !path.startsWith("/")
                    && !path.contains("\\");
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** Kahn's algorithm: rejects dangling edges and cycles (rework must use {@code rejectTo}). */
    private static void validateDag(Set<String> ids, List<WorkflowEdge> edges, List<String> problems) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (WorkflowEdge edge : edges) {
            if (!ids.contains(edge.source()) || !ids.contains(edge.target())) {
                problems.add("edge " + edge.id() + " references a missing node");
                return;
            }
            indegree.merge(edge.target(), 1, Integer::sum);
            adjacency.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
        }
        Deque<String> queue = new ArrayDeque<>();
        for (String id : ids) {
            if (indegree.getOrDefault(id, 0) == 0) {
                queue.add(id);
            }
        }
        int seen = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            seen++;
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (seen != ids.size()) {
            problems.add("workflow must be a DAG; use rejectTo for controlled rework");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
