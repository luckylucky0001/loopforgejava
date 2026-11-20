package io.github.loopforge4j.server.account;

import io.github.loopforge4j.server.config.LoopForgeProperties;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for personal credentials. The key is {@code loopforge.security.credential-key}, or one
 * generated on first use and kept in {@code app_settings} (then a database backup holds both key and
 * ciphertext: configure the key to keep them apart). Each ciphertext is bound to its owner and kind,
 * so a row copied onto another account does not decrypt.
 */
@Component
public class CredentialCipher {

    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
    private static final String KEY_SETTING = "credential.key";
    private static final String VERSION = "v1:";
    private static final int IV_BYTES = 12;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final String configured;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKeySpec key;

    public CredentialCipher(JdbcClient jdbc, Clock clock, LoopForgeProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.configured = properties.security().credentialKey();
    }

    public String encrypt(String plaintext, String binding) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION
                    + Base64.getEncoder()
                            .encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                                    .put(iv)
                                    .put(sealed)
                                    .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot encrypt a credential", e);
        }
    }

    /** The plaintext, or empty when the ciphertext does not open with this key and binding. */
    public Optional<String> decrypt(String ciphertext, String binding) {
        if (ciphertext == null || !ciphertext.startsWith(VERSION)) {
            return Optional.empty();
        }
        try {
            byte[] raw = Base64.getDecoder().decode(ciphertext.substring(VERSION.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, raw, 0, IV_BYTES));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            return Optional.of(
                    new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private SecretKeySpec key() {
        SecretKeySpec k = key;
        if (k == null) {
            synchronized (this) {
                k = key;
                if (k == null) {
                    k = new SecretKeySpec(loadKey(), "AES");
                    key = k;
                }
            }
        }
        return k;
    }

    private byte[] loadKey() {
        if (configured != null && !configured.isBlank()) {
            byte[] bytes = Base64.getDecoder().decode(configured.strip());
            if (bytes.length != 32) {
                throw new IllegalStateException("loopforge.security.credential-key must be 32 bytes (base64)");
            }
            return bytes;
        }
        Optional<String> stored = read();
        if (stored.isEmpty()) {
            byte[] fresh = new byte[32];
            random.nextBytes(fresh);
            try {
                jdbc.sql("INSERT INTO app_settings (setting_name, setting_value, updated_at) VALUES (:n, :v, :now)")
                        .param("n", KEY_SETTING)
                        .param("v", Base64.getEncoder().encodeToString(fresh))
                        .param("now", SqlTime.of(clock.instant()))
                        .update();
                log.warn("generated the personal-credential key in the database; set"
                        + " loopforge.security.credential-key to keep it out of database backups");
            } catch (DuplicateKeyException e) {
                // another instance created it first
            }
            stored = read();
        }
        return Base64.getDecoder().decode(stored.orElseThrow());
    }

    private Optional<String> read() {
        return jdbc.sql("SELECT setting_value FROM app_settings WHERE setting_name = :n")
                .param("n", KEY_SETTING)
                .query(String.class)
                .optional();
    }
}
