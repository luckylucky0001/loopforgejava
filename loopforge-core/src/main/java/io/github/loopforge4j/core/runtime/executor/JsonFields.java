package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.domain.TokenUsage;
import tools.jackson.databind.JsonNode;

/** Null-safe accessors for loosely typed CLI JSON frames. */
final class JsonFields {

    private JsonFields() {}

    static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.isString() ? value.stringValue() : value.toString();
    }

    static long number(JsonNode node, String... fields) {
        if (node == null) {
            return 0;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isNumber() && value.longValue() != 0) {
                return value.longValue();
            }
        }
        return 0;
    }

    static boolean bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() && value.booleanValue();
    }

    /** Port of upstream {@code tokenUsage}: accepts Anthropic, OpenAI and Codex field names. */
    static TokenUsage usage(JsonNode usage, JsonNode costHolder) {
        if (usage == null || !usage.isObject()) {
            usage = null;
        }
        long input = number(usage, "input_tokens", "prompt_tokens", "inputTokens");
        long output = number(usage, "output_tokens", "completion_tokens", "outputTokens");
        long cacheRead = number(usage, "cache_read_input_tokens", "cached_input_tokens", "cache_read_tokens");
        long cacheWrite = number(usage, "cache_creation_input_tokens", "cache_write_tokens");
        long reasoning = number(usage, "reasoning_tokens", "reasoning_output_tokens");
        long total = number(usage, "total_tokens", "totalTokens");
        double cost = 0;
        String costStatus = null;
        JsonNode costNode = costHolder == null ? null : costHolder.get("total_cost_usd");
        if (costNode != null && costNode.isNumber()) {
            cost = costNode.doubleValue();
            costStatus = "reported";
        }
        return new TokenUsage(input, output, cacheRead, cacheWrite, reasoning, total, cost, costStatus);
    }

    static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
