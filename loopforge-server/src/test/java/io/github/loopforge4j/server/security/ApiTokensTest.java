package io.github.loopforge4j.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ApiTokensTest {

    @TempDir
    Path dir;

    @Test
    void generatesAnOwnerOnlyTokenOnceAndReusesIt() throws Exception {
        Path file = dir.resolve("sub/api-token");
        ApiTokens first = new ApiTokens(properties(file, List.of()));
        String token = Files.readString(file).strip();
        assertThat(token.length()).isGreaterThanOrEqualTo(32);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
        assertThat(first.authenticate(token)).isPresent();
        ApiTokens second = new ApiTokens(properties(file, List.of()));
        assertThat(second.authenticate(token)).isPresent();
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertThat(files).hasSize(1); // no temp file left behind
        }
    }

    @Test
    void rejectsAnEmptyOrTruncatedTokenFile() throws Exception {
        Path file = dir.resolve("api-token");
        Files.writeString(file, "short");
        assertThatThrownBy(() -> new ApiTokens(properties(file, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no valid token");
    }

    @Test
    void configuredTokensMustBeLongAndAreComparedByDigest() {
        assertThatThrownBy(() -> new ApiTokens(properties(dir.resolve("unused"), List.of("too-short"))))
                .isInstanceOf(IllegalStateException.class);
        ApiTokens tokens = new ApiTokens(properties(dir.resolve("unused"), List.of("x".repeat(40))));
        assertThat(tokens.authenticate("x".repeat(40)))
                .get()
                .asString()
                .startsWith("token-")
                .doesNotContain("xxxx");
        assertThat(tokens.authenticate("x".repeat(39))).isEmpty();
        assertThat(tokens.authenticate(null)).isEmpty();
        assertThat(Files.exists(dir.resolve("unused"))).isFalse();
    }

    private static LoopForgeProperties properties(Path tokenFile, List<String> tokens) {
        Map<String, Object> values = new HashMap<>();
        values.put("loopforge.security.token-file", tokenFile.toString());
        for (int i = 0; i < tokens.size(); i++) {
            values.put("loopforge.security.api-tokens[" + i + "]", tokens.get(i));
        }
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("loopforge", LoopForgeProperties.class);
    }
}
