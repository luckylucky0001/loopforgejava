package io.github.loopforge4j.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretsTest {

    @Test
    void secretLookingValuesAreMaskedLongestFirst() {
        var secrets = Secrets.values(Map.of(
                "ANTHROPIC_API_KEY", "sk-abcdef123456",
                "LOOPFORGE_GIT_TOKEN__github_com", "ghp_token_1234",
                "GH_TOKEN", "ghp_token_1234-and-more",
                "GIT_CONFIG_KEY_0", "credential.helper",
                "OPENAI_BASE_URL", "https://gateway.example/v1",
                "SHORT_TOKEN", "abc"));
        assertThat(secrets).containsExactly("ghp_token_1234-and-more", "sk-abcdef123456", "ghp_token_1234");
        assertThat(Secrets.redact("key sk-abcdef123456 gh ghp_token_1234-and-more via credential.helper", secrets))
                .isEqualTo("key *** gh *** via credential.helper");
        assertThat(Secrets.redact(null, secrets)).isNull();
    }

    @Test
    void theRedactingSinkMasksMessagesAndTextAttributes() {
        List<Telemetry> seen = new ArrayList<>();
        EventSink sink = Secrets.redacting(seen::add, List.of("sk-abcdef123456"));
        sink.emit(new Telemetry(
                "tool",
                "tool.call",
                "finish",
                "info",
                "echo sk-abcdef123456",
                null,
                null,
                null,
                Map.of("input", "export K=sk-abcdef123456", "count", 3)));
        assertThat(seen).singleElement().satisfies(t -> {
            assertThat(t.message()).isEqualTo("echo ***");
            assertThat(t.attributes()).containsEntry("input", "export K=***").containsEntry("count", 3);
        });
        EventSink plain = event -> {};
        assertThat(Secrets.redacting(plain, List.of())).isSameAs(plain);
    }
}
