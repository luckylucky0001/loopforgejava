package io.github.loopforge4j.server.security;

import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Accepted API tokens, kept only as SHA-256 digests and compared in constant time. Without configured
 * tokens a random one is generated into an owner-only file (never logged).
 */
@Component
public class ApiTokens {

    private static final Logger log = LoggerFactory.getLogger(ApiTokens.class);
    private static final int MIN_LENGTH = 32;

    private final List<byte[]> digests = new ArrayList<>();

    public ApiTokens(LoopForgeProperties properties) {
        List<String> configured = properties.security().apiTokens().stream()
                .filter(t -> !t.isBlank())
                .toList();
        for (String token : configured) {
            if (token.length() < MIN_LENGTH) {
                throw new IllegalStateException(
                        "loopforge.security.api-tokens entries must have at least " + MIN_LENGTH + " characters");
            }
            digests.add(sha256(token));
        }
        if (configured.isEmpty()) {
            digests.add(sha256(generatedToken(properties.security().tokenFile())));
        }
    }

    /** Principal name for a valid token: a short digest prefix, never the token itself. */
    public Optional<String> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        byte[] candidate = sha256(token.strip());
        boolean match = false;
        for (byte[] digest : digests) {
            match |= MessageDigest.isEqual(digest, candidate);
        }
        return match
                ? Optional.of("token-" + HexFormat.of().formatHex(candidate).substring(0, 8))
                : Optional.empty();
    }

    private static String generatedToken(Path file) {
        try {
            if (Files.exists(file)) {
                return readExisting(file);
            }
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            Path temp = createPrivateTempFile(parent);
            try {
                Files.writeString(temp, token + "\n", StandardCharsets.UTF_8);
                // A hard link publishes the complete file atomically and never replaces an existing one,
                // so concurrent starters agree on one token and nobody reads a half-written file.
                Files.createLink(file, temp);
            } catch (FileAlreadyExistsException e) {
                return readExisting(file);
            } catch (UnsupportedOperationException e) {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            log.warn(
                    "No loopforge.security.api-tokens configured; generated an API token in {}."
                            + " Configure LOOPFORGE_SECURITY_APITOKENS for production.",
                    file.toAbsolutePath());
            return token;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create API token file " + file, e);
        }
    }

    private static Path createPrivateTempFile(Path directory) throws IOException {
        try {
            return Files.createTempFile(
                    directory,
                    ".api-token",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            log.warn("file system has no POSIX permissions; restrict access to {} manually", directory);
            return Files.createTempFile(directory, ".api-token", ".tmp");
        }
    }

    private static String readExisting(Path file) throws IOException {
        String token = Files.readString(file, StandardCharsets.UTF_8).strip();
        if (token.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "API token file " + file.toAbsolutePath() + " holds no valid token (at least " + MIN_LENGTH
                            + " characters); delete it to generate a new one");
        }
        try {
            var permissions = Files.getPosixFilePermissions(file);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)) {
                log.warn("API token file {} is readable by other users; run chmod 600 on it", file.toAbsolutePath());
            }
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system
        }
        return token;
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
