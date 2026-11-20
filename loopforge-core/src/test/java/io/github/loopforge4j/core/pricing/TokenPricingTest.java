package io.github.loopforge4j.core.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.loopforge4j.core.domain.TokenUsage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenPricingTest {

    final TokenPricing pricing = TokenPricing.defaults();

    @Test
    void estimatesWithTheUpstreamTable() {
        TokenUsage usage = new TokenUsage(1_000_000, 100_000, 2_000_000, 0, 0, 0, 0, null);
        TokenUsage priced = pricing.price("claude-sonnet-4.5-20250929", usage, false);
        // 1M * 3 + 0.1M * 15 + 2M * 0.3 = 3 + 1.5 + 0.6
        assertThat(priced.costUsd()).isCloseTo(5.1, within(1e-9));
        assertThat(priced.costStatus()).isEqualTo("estimated");
        // inclusive input: cached tokens are part of the input count
        TokenUsage codex = new TokenUsage(3_000_000, 0, 2_000_000, 0, 0, 0, 0, null);
        assertThat(pricing.price("gpt-5.4", codex, true).costUsd()).isCloseTo(2.5 + 0.5, within(1e-9));
        // CNY prices convert to USD
        TokenUsage glm = new TokenUsage(7_000_000, 0, 0, 0, 0, 0, 0, null);
        assertThat(pricing.price("GLM-5.3", glm, false).costUsd()).isCloseTo(8, within(1e-9));
    }

    @Test
    void reportedUnknownAndEmptyUsage() {
        TokenUsage reported = new TokenUsage(10, 10, 0, 0, 0, 0, 0.42, "reported");
        assertThat(pricing.price("claude-opus-4", reported, false)).isSameAs(reported);
        assertThat(pricing.price("mystery-model", new TokenUsage(10, 10, 0, 0, 0, 0, 0, null), false)
                        .costStatus())
                .isEqualTo("unknown");
        assertThat(pricing.price("mystery-model", TokenUsage.ZERO, false).costStatus())
                .isEqualTo("estimated");
        assertThat(pricing.price("gpt-5.4", new TokenUsage(1, 0, 5, 0, 0, 0, 0, null), true)
                        .costStatus())
                .isEqualTo("unknown");
    }

    @Test
    void overridesReplaceAndExtendTheTable() {
        TokenPricing custom = TokenPricing.withOverrides(Map.of(
                "My-Model", new TokenPricing.Price("USD", 1, 2, 0, 0),
                "claude-opus-4", new TokenPricing.Price("USD", 1, 1, 0, 0)));
        TokenUsage usage = new TokenUsage(1_000_000, 1_000_000, 0, 0, 0, 0, 0, null);
        assertThat(custom.price("my-model", usage, false).costUsd()).isCloseTo(3, within(1e-9));
        assertThat(custom.price("claude-opus-4", usage, false).costUsd()).isCloseTo(2, within(1e-9));
        assertThat(TokenPricing.normalize("custom-local:Claude-Opus-4.5-internal-ioa"))
                .isEqualTo("claude-opus-4-5");
    }
}
