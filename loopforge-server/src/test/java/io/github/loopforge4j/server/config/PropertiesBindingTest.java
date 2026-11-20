package io.github.loopforge4j.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Guards the documented environment variable names (relaxed binding drops dashes, not underscores). */
class PropertiesBindingTest {

    @Test
    void documentedEnvironmentVariablesBind() {
        StandardEnvironment environment = new StandardEnvironment();
        // Spring applies environment-variable name mapping only to the "systemEnvironment" source.
        String name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        Map<String, Object> variables = Map.of(
                "LOOPFORGE_SECURITY_APITOKENS", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "LOOPFORGE_DEMOCATALOG", "false",
                "LOOPFORGE_ENGINE_DEFAULTEXECUTOR", "codex",
                "LOOPFORGE_ENGINE_NODETIMEOUT", "30m");
        environment.getPropertySources().replace(name, new SystemEnvironmentPropertySource(name, variables));
        Iterable<ConfigurationPropertySource> sources = ConfigurationPropertySources.get(environment);
        LoopForgeProperties properties = new Binder(sources).bindOrCreate("loopforge", LoopForgeProperties.class);

        assertThat(properties.security().apiTokens()).hasSize(2);
        assertThat(properties.demoCatalog()).isFalse();
        assertThat(properties.engine().defaultExecutor()).isEqualTo("codex");
        assertThat(properties.engine().nodeTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.sandbox().privateHome()).isTrue();
    }

    @Test
    void credentialsBindFromYamlStyleKeys() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("loopforge.credentials.claude-code.ANTHROPIC_API_KEY", "sk-1"));
        LoopForgeProperties properties = new Binder(source).bindOrCreate("loopforge", LoopForgeProperties.class);
        assertThat(properties.credentials()).containsKey("claude-code");
        assertThat(properties.credentials().get("claude-code")).containsEntry("ANTHROPIC_API_KEY", "sk-1");
    }
}
