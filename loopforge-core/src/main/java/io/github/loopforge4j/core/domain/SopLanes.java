package io.github.loopforge4j.core.domain;

import java.util.List;
import java.util.Locale;

/**
 * The controlled role-chain tag of an SOP (upstream {@code SOPLane}): which chain of a requirement
 * the SOP is (product / frontend / backend / design / repair / other). Several lanes run side by
 * side on one requirement.
 */
public final class SopLanes {

    public static final String PRODUCT = "product";
    public static final String FRONTEND = "frontend";
    public static final String BACKEND = "backend";
    public static final String DESIGN = "design";
    public static final String REPAIR = "repair";
    public static final String OTHER = "other";

    /** Display order of a requirement's role chains. */
    public static final List<String> ORDER = List.of(PRODUCT, FRONTEND, BACKEND, DESIGN, REPAIR, OTHER);

    private SopLanes() {}

    public static String normalize(String raw) {
        if (raw == null) {
            return OTHER;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case PRODUCT, "产品", "prd" -> PRODUCT;
            case FRONTEND, "front", "前端" -> FRONTEND;
            case BACKEND, "back", "后端" -> BACKEND;
            case DESIGN, "设计", "uiux" -> DESIGN;
            case REPAIR, "fix", "issue", "bugfix", "修复", "缺陷" -> REPAIR;
            default -> OTHER;
        };
    }

    public static String label(String lane) {
        return switch (normalize(lane)) {
            case PRODUCT -> "产品";
            case FRONTEND -> "前端";
            case BACKEND -> "后端";
            case DESIGN -> "设计";
            case REPAIR -> "修复";
            default -> "其他";
        };
    }

    /** The explicit tag, else inferred from the id, name and tags (upstream {@code InferSOPLane}). */
    public static String infer(WorkflowDefinition workflow) {
        if (workflow == null) {
            return OTHER;
        }
        if (workflow.sopLane() != null && !workflow.sopLane().isBlank()) {
            return normalize(workflow.sopLane());
        }
        String hay = (workflow.id() + " " + workflow.name() + " " + String.join(" ", workflow.tags()))
                .toLowerCase(Locale.ROOT);
        if (hay.contains("generic-product")
                || hay.contains("产品交付")
                || hay.contains("product") && (hay.contains("delivery") || hay.contains("prd"))) {
            return PRODUCT;
        }
        if (hay.contains("generic-frontend")
                || hay.contains("前端交付")
                || hay.contains("frontend") && hay.contains("delivery")) {
            return FRONTEND;
        }
        if (hay.contains("generic-design") || hay.contains("设计交付") || hay.contains("设计") && !hay.contains("需求开发")) {
            return DESIGN;
        }
        if (hay.contains("generic-backend")
                || hay.contains("后端交付")
                || hay.contains("requirement-development")
                || hay.contains("loopforge")
                || hay.contains("需求开发")) {
            return BACKEND;
        }
        if (hay.contains("issue-fix") || hay.contains("缺陷修复") || hay.contains("修复") && !hay.contains("交付")) {
            return REPAIR;
        }
        return OTHER;
    }
}
