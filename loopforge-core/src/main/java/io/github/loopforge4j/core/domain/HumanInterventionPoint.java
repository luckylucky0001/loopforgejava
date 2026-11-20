package io.github.loopforge4j.core.domain;

/** Reusable human checkpoint owned by an agent role. */
public record HumanInterventionPoint(
        String id, String name, InterventionStage stage, boolean required, String instructions) {}
