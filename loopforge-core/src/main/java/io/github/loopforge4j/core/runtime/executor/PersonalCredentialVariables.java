package io.github.loopforge4j.core.runtime.executor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a person's own model credentials (key, base URL, extra variables) become the environment of
 * an executor kind, and which extra variable names a person may set. Only credential-like names are
 * accepted: a person must not be able to change how the CLI or the sandbox starts (PATH, LD_*,
 * NODE_OPTIONS, …) or impersonate the platform's own variables.
 */
public final class PersonalCredentialVariables {

    private static final Pattern NAME = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    private static final List<String> SUFFIXES = List.of(
            "_API_KEY",
            "_AUTH_TOKEN",
            "_OAUTH_TOKEN",
            "_ACCESS_TOKEN",
            "_BASE_URL",
            "_ENDPOINT",
            "_MODEL",
            "_MODELS",
            "_MODEL_NAME",
            "_REGION",
            "_PROJECT",
            "_PROJECT_ID",
            "_ORG_ID",
            "_ORGANIZATION",
            "_PROTOCOL",
            "_PROVIDER_TYPE",
            "_API_VERSION");
    private static final List<String> RESERVED_PREFIXES =
            List.of("LOOPFORGE_", "GIT_", "SSH_", "LD_", "DYLD_", "NODE_", "JAVA_", "PYTHON", "NPM_", "GH_", "GITLAB_");

    /** Key and base URL variables per executor kind (upstream personal executor credentials). */
    private static final Map<String, Mapping> KINDS = Map.of(
            ClaudeCodeExecutor.KIND, new Mapping(List.of("ANTHROPIC_API_KEY"), List.of("ANTHROPIC_BASE_URL")),
            CodexExecutor.KIND, new Mapping(List.of("OPENAI_API_KEY", "CODEX_API_KEY"), List.of("OPENAI_BASE_URL")),
            CodeBuddyExecutor.KIND, new Mapping(List.of("CODEBUDDY_API_KEY"), List.of("CODEBUDDY_BASE_URL")),
            AgentCliExecutor.OPENCODE, new Mapping(List.of("OPENAI_API_KEY"), List.of("OPENAI_BASE_URL")),
            AgentCliExecutor.CURSOR, new Mapping(List.of("CURSOR_API_KEY"), List.of()),
            AgentCliExecutor.ANTIGRAVITY,
                    new Mapping(List.of("GEMINI_API_KEY"), List.of("GEMINI_BASE_URL", "GOOGLE_GEMINI_BASE_URL")),
            AgentCliExecutor.KIMI, new Mapping(List.of("KIMI_API_KEY", "MOONSHOT_API_KEY"), List.of("KIMI_BASE_URL")),
            AgentCliExecutor.PI, new Mapping(List.of("OPENAI_API_KEY"), List.of("OPENAI_BASE_URL")),
            AgentCliExecutor.DEEPSEEK, new Mapping(List.of("DEEPSEEK_API_KEY"), List.of("DEEPSEEK_BASE_URL")),
            AgentCliExecutor.ZCODE, new Mapping(List.of("ZCODE_API_KEY"), List.of("ZCODE_BASE_URL")));

    private record Mapping(List<String> keys, List<String> baseUrls) {}

    private PersonalCredentialVariables() {}

    /** Executor kinds a person can bring credentials for. */
    public static Set<String> kinds() {
        return KINDS.keySet();
    }

    /** Whether a person may set {@code name} as an extra variable. */
    public static boolean allowed(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return false;
        }
        for (String prefix : RESERVED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return false;
            }
        }
        return SUFFIXES.stream().anyMatch(name::endsWith);
    }

    /** The environment for {@code kind}: the key and base URL variables, then the extra variables. */
    public static Map<String, String> environment(
            String kind, String apiKey, String baseUrl, Map<String, String> extra) {
        Map<String, String> env = new LinkedHashMap<>();
        Mapping mapping = KINDS.get(kind);
        if (mapping != null) {
            if (apiKey != null && !apiKey.isBlank()) {
                mapping.keys().forEach(k -> env.put(k, apiKey.strip()));
            }
            if (baseUrl != null && !baseUrl.isBlank()) {
                mapping.baseUrls().forEach(k -> env.put(k, baseUrl.strip()));
            }
        }
        if (extra != null) {
            extra.forEach((name, value) -> {
                if (allowed(name) && value != null) {
                    env.put(name, value);
                }
            });
        }
        return env;
    }
}
