package io.github.loopforge4j.server.account;

/** Membership of an account in a project. */
public record ProjectMember(String projectId, String userId, String username, ProjectRole role) {}
