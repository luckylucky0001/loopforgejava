package io.github.loopforge4j.core.domain;

import java.util.Set;

/**
 * A role-scoped lifecycle script compiled into the native hook configuration of Claude Code, Codex
 * and CodeBuddy (upstream {@code AgentHook}).
 *
 * @param event {@code SessionStart}, {@code UserPromptSubmit}, {@code PreToolUse}, {@code
 *     PostToolUse} or {@code Stop}
 * @param matcher tool name pattern for tool events
 * @param language {@code shell} or {@code python}
 * @param executionScope run once at the workspace root, or once per bound repository
 */
public record AgentHook(
        String id,
        String name,
        String event,
        String matcher,
        ScriptLanguage language,
        String script,
        int timeoutSec,
        ExecutionScope executionScope) {

    public static final Set<String> EVENTS =
            Set.of("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop");

    public AgentHook {
        id = id == null ? "" : id.strip();
        name = name == null || name.isBlank() ? id : name;
        language = language == null ? ScriptLanguage.SHELL : language;
        script = script == null ? "" : script;
        timeoutSec = timeoutSec <= 0 ? 60 : Math.min(timeoutSec, 600);
        matcher = matcher == null || matcher.isBlank() ? null : matcher;
    }

    public ExecutionScope effectiveScope() {
        return executionScope == null ? ExecutionScope.RUN : executionScope;
    }
}
