package io.github.loopforge4j.server.account;

import java.security.Principal;

/**
 * Authenticated caller. Configured API tokens authenticate as a service principal with
 * administrator rights; account tokens as the account; run tokens as the agent of one running node.
 *
 * @param id account id, {@code null} for the service principal and agents
 * @param agent set for an agent's run token: read-only viewer of exactly the run's project
 */
public record CurrentUser(String id, String username, Role role, boolean service, AgentGrant agent)
        implements Principal {

    public static final CurrentUser SERVICE = new CurrentUser(null, "operator", Role.ADMIN, true, null);

    public CurrentUser(String id, String username, Role role, boolean service) {
        this(id, username, role, service, null);
    }

    public static CurrentUser of(User user) {
        return new CurrentUser(user.id(), user.username(), user.role(), false, null);
    }

    /** The agent of a running node, authenticated with a platform run token. */
    public static CurrentUser agent(AgentGrant grant) {
        return new CurrentUser(null, "agent:" + grant.agentId(), Role.MEMBER, false, grant);
    }

    public boolean admin() {
        return role == Role.ADMIN && agent == null;
    }

    /** Agents only read; the API rejects their mutating requests before any controller runs. */
    public boolean readOnly() {
        return agent != null && !agent.delegated();
    }

    @Override
    public String getName() {
        return username;
    }

    /**
     * @param projectId the run's project; {@code null} grants no project at all
     */
    public record AgentGrant(String runId, String projectId, String nodeId, String agentId, boolean delegated) {

        public AgentGrant(String runId, String projectId, String nodeId, String agentId) {
            this(runId, projectId, nodeId, agentId, false);
        }
    }

    /**
     * The conversation assistant acting for {@code user} during one turn: the user's own project
     * roles (only the session's project for a project conversation), never administrator rights.
     */
    public static CurrentUser assistantFor(User user, String sessionId, String projectId) {
        return assistantFor(user, sessionId, projectId, null);
    }

    /** As above, for the turn {@code turnId} (its proposed writes attach to that turn). */
    public static CurrentUser assistantFor(User user, String sessionId, String projectId, String turnId) {
        return new CurrentUser(
                user.id(),
                user.username(),
                Role.MEMBER,
                false,
                new AgentGrant("conv:" + sessionId, projectId, turnId, "main-bot", true));
    }
}
