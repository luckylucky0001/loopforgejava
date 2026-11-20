package io.github.loopforge4j.core.domain;

/** One finding attached to a gate result. */
public record GateFinding(String severity, String title, String evidence) {}
