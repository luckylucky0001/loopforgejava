package io.github.loopforge4j.core.pricing;

import io.github.loopforge4j.core.domain.TokenUsage;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Estimates the cost of token usage a CLI did not price itself (upstream {@code token_pricing.go},
 * pricing version {@value #DEFAULT_VERSION}). Prices are per million tokens; CNY prices are converted
 * at {@value #USD_TO_CNY} CNY/USD. Deployments override or extend the table through configuration.
 */
public final class TokenPricing {

    public static final String DEFAULT_VERSION = "public-standard-2026-11-05";
    public static final double USD_TO_CNY = 7.0;
    private static final Pattern SNAPSHOT_SUFFIX = Pattern.compile("-(?:\\d{8}|\\d{4}-\\d{2}-\\d{2})$");
    private static final Pattern CLAUDE_DOTS = Pattern.compile("(claude-(?:opus|sonnet|haiku|fable)-\\d+)\\.(\\d+)");

    /** Price of one model per million tokens. */
    public record Price(String currency, double input, double output, double cacheRead, double cacheWrite) {}

    private final Map<String, Price> prices;

    private TokenPricing(Map<String, Price> prices) {
        this.prices = Map.copyOf(prices);
    }

    /** The upstream public price table. */
    public static TokenPricing defaults() {
        return new TokenPricing(DEFAULTS);
    }

    /** The default table with {@code overrides} replacing or adding models. */
    public static TokenPricing withOverrides(Map<String, Price> overrides) {
        Map<String, Price> merged = new HashMap<>(DEFAULTS);
        overrides.forEach((model, price) -> merged.put(normalize(model), price));
        return new TokenPricing(merged);
    }

    /**
     * Usage with an estimated cost; usage that already carries a reported cost is returned unchanged.
     *
     * @param inclusiveInput the CLI counts cached tokens inside input tokens (OpenAI semantics)
     */
    public TokenUsage price(String model, TokenUsage usage, boolean inclusiveInput) {
        if (usage == null || "reported".equals(usage.costStatus())) {
            return usage;
        }
        if (usage.inputTokens() < 0
                || usage.outputTokens() < 0
                || usage.cacheReadTokens() < 0
                || usage.cacheWriteTokens() < 0) {
            return with(usage, 0, "unknown");
        }
        long billable = usage.inputTokens() + usage.outputTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens();
        if (billable == 0) {
            return with(usage, 0, "estimated");
        }
        Price price = model == null ? null : prices.get(normalize(model));
        if (price == null) {
            return with(usage, 0, "unknown");
        }
        long input = usage.inputTokens();
        if (inclusiveInput) {
            input -= usage.cacheReadTokens() + usage.cacheWriteTokens();
            if (input < 0) {
                return with(usage, 0, "unknown");
            }
        }
        double cost = (input * price.input()
                        + usage.outputTokens() * price.output()
                        + usage.cacheReadTokens() * price.cacheRead()
                        + usage.cacheWriteTokens() * price.cacheWrite())
                / 1e6;
        if ("CNY".equalsIgnoreCase(price.currency())) {
            cost /= USD_TO_CNY;
        }
        return with(usage, cost, "estimated");
    }

    static String normalize(String model) {
        String m = model.strip().toLowerCase(Locale.ROOT);
        m = m.startsWith("custom-local:") ? m.substring("custom-local:".length()) : m;
        if (m.endsWith("-internal-ioa")) {
            m = m.substring(0, m.length() - "-internal-ioa".length());
        } else if (m.endsWith("-ioa")) {
            m = m.substring(0, m.length() - 4);
        }
        if (m.endsWith("-1m")) {
            m = m.substring(0, m.length() - 3);
        }
        m = CLAUDE_DOTS.matcher(m).replaceAll("$1-$2");
        m = SNAPSHOT_SUFFIX.matcher(m).replaceAll("");
        return switch (m) {
            case "deepseek-v4-flash",
                    "deepseek-v4-flash-vision-exp",
                    "deepseek-v4.1-flash",
                    "deepseek-v4.1-flash-1017" -> "deepseek-flash";
            case "kimi-k2.7" -> "kimi-k2.7-code";
            case "gemini-3.1-pro" -> "gemini-3.1-pro-preview";
            default -> m;
        };
    }

    private static TokenUsage with(TokenUsage usage, double cost, String status) {
        return new TokenUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadTokens(),
                usage.cacheWriteTokens(),
                usage.reasoningTokens(),
                usage.totalTokens(),
                cost,
                status);
    }

    private static Price usd(double in, double out, double read, double write) {
        return new Price("USD", in, out, read, write);
    }

    private static Price cny(double in, double out, double read, double write) {
        return new Price("CNY", in, out, read, write);
    }

    private static final Map<String, Price> DEFAULTS = Map.ofEntries(
            Map.entry("gpt-5.5", usd(5, 30, .5, 5)),
            Map.entry("claude-haiku-5-5", usd(.1, .5, .01, .125)),
            Map.entry("gpt-6-astra", usd(10, 50, 1, 12.5)),
            Map.entry("gpt-6.1-sol", usd(2, 10, .1, 2.5)),
            Map.entry("gpt-6.1-terra", usd(1, 5, .05, 1.25)),
            Map.entry("gpt-6-sol", usd(2, 10, .2, 2.5)),
            Map.entry("gpt-6-luna", usd(.1, .5, .01, .125)),
            Map.entry("gpt-5.6-sol", usd(4, 20, .4, 5)),
            Map.entry("gpt-5.6-terra", usd(2, 12, .2, 2.5)),
            Map.entry("gpt-5.6-luna", usd(.2, 1.2, .02, .25)),
            Map.entry("gpt-5.4", usd(2.5, 15, .25, 2.5)),
            Map.entry("gpt-5.3-codex", usd(1.75, 14, .175, 1.75)),
            Map.entry("gpt-5.2", usd(1.75, 14, .175, 1.75)),
            Map.entry("claude-fable-5-1", usd(10, 50, .25, 12.5)),
            Map.entry("claude-fable-5", usd(10, 50, 1, 12.5)),
            Map.entry("claude-opus-5-5", usd(4, 20, .2, 5)),
            Map.entry("claude-opus-5", usd(5, 25, .5, 6.25)),
            Map.entry("claude-opus-4-8", usd(5, 25, .5, 6.25)),
            Map.entry("claude-opus-4-7", usd(5, 25, .5, 6.25)),
            Map.entry("claude-opus-4-6", usd(5, 25, .5, 6.25)),
            Map.entry("claude-opus-4-5", usd(5, 25, .5, 6.25)),
            Map.entry("claude-opus-4-1", usd(15, 75, 1.5, 18.75)),
            Map.entry("claude-opus-4", usd(15, 75, 1.5, 18.75)),
            Map.entry("claude-sonnet-5-5", usd(2, 10, .1, 2.5)),
            Map.entry("claude-sonnet-5", usd(2, 10, .2, 2.5)),
            Map.entry("claude-sonnet-4-6", usd(3, 15, .3, 3.75)),
            Map.entry("claude-sonnet-4-5", usd(3, 15, .3, 3.75)),
            Map.entry("claude-sonnet-4", usd(3, 15, .3, 3.75)),
            Map.entry("claude-haiku-4-5", usd(1, 5, .1, 1.25)),
            Map.entry("claude-haiku-3-5", usd(.8, 4, .08, 1)),
            Map.entry("deepseek-flash", cny(2, 8, .04, 2)),
            Map.entry("deepseek-v4-pro", cny(9, 27, .3, 9)),
            Map.entry("glm-5.3", cny(8, 28, 2, 8)),
            Map.entry("glm-5.2", cny(8, 28, 2, 8)),
            Map.entry("glm-5.3-flash", cny(.8, 2.8, .23, .8)),
            Map.entry("glm-5.3-flashx", cny(2, 7, .57, 2)),
            Map.entry("glm-5v-turbo", cny(5, 22, 1.2, 5)),
            Map.entry("kimi-k3.1-pro", cny(13, 68, 1.3, 13)),
            Map.entry("kimi-k3", cny(20, 100, 2, 20)),
            Map.entry("kimi-k2.8-preview", cny(6.5, 27, 1.7, 6.5)),
            Map.entry("kimi-k2.7-code", cny(6.5, 27, 1.3, 6.5)),
            Map.entry("kimi-k2.6", cny(6.5, 27, 1.1, 6.5)),
            Map.entry("minimax-m3", cny(2.1, 8.4, .42, 2.1)),
            Map.entry("minimax-m2.7", cny(2.1, 8.4, .42, 2.1)),
            Map.entry("minimax-m3.1-flash", cny(1, 4, .1, 1)),
            Map.entry("mimo-v2.6-pro", cny(3, 6, .025, 3)),
            Map.entry("qwen3-coder-plus", cny(4, 16, .4, 5)),
            Map.entry("qwen3-coder-flash", cny(1, 4, .1, 1.25)),
            Map.entry("qwen3.8-max", cny(12, 36, 1.2, 15)),
            Map.entry("gemini-3.1-pro-preview", usd(2, 12, .2, 2)),
            Map.entry("gemini-4-argon", usd(2, 10, .1, 2)));
}
