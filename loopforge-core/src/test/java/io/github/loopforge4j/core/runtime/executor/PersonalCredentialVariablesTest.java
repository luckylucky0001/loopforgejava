package io.github.loopforge4j.core.runtime.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PersonalCredentialVariablesTest {

    @Test
    void onlyCredentialLikeNamesAreAllowed() {
        assertThat(PersonalCredentialVariables.allowed("ANTHROPIC_AUTH_TOKEN")).isTrue();
        assertThat(PersonalCredentialVariables.allowed("AZURE_OPENAI_ENDPOINT")).isTrue();
        assertThat(PersonalCredentialVariables.allowed("KIMI_MODEL_NAME")).isTrue();
        for (String name : new String[] {
            "PATH", "HOME", "LD_PRELOAD", "NODE_OPTIONS", "JAVA_TOOL_OPTIONS", "PYTHONPATH", "LOOPFORGE_RUN_ID",
            "GIT_ASKPASS", "GH_TOKEN", "GITLAB_TOKEN", "SSH_AUTH_SOCK", "LD_API_KEY", "anthropic_api_key", "A",
            "HTTPS_PROXY", "NODE_EXTRA_CA_CERTS", null
        }) {
            assertThat(PersonalCredentialVariables.allowed(name)).as(name).isFalse();
        }
    }

    @Test
    void keyAndBaseUrlMapToTheKindsVariables() {
        assertThat(PersonalCredentialVariables.environment(
                        "claude-code", " sk-1 ", "https://gw.example/anthropic", Map.of("ANTHROPIC_MODEL", "m")))
                .containsExactly(
                        Map.entry("ANTHROPIC_API_KEY", "sk-1"),
                        Map.entry("ANTHROPIC_BASE_URL", "https://gw.example/anthropic"),
                        Map.entry("ANTHROPIC_MODEL", "m"));
        assertThat(PersonalCredentialVariables.environment("codex", "k", null, Map.of("PATH", "/evil")))
                .containsOnlyKeys("OPENAI_API_KEY", "CODEX_API_KEY");
        assertThat(PersonalCredentialVariables.environment("unknown", "k", "u", null))
                .isEmpty();
        assertThat(PersonalCredentialVariables.kinds()).contains("claude-code", "codex", "codebuddy", "agy");
    }
}
