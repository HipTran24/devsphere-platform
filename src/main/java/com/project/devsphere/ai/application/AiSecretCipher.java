package com.project.devsphere.ai.application;

import com.project.devsphere.ai.infrastructure.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * AES-256-GCM cipher for encrypting workspace AI provider credentials prior to JSONB persistence
 * under {@code "encryptedCredential"} (complying with the {@code V006} {@code ds_json_has_secret} guard).
 *
 * <p>Requires an explicit 32-byte Base64 {@code devsphere.ai.encryption-key} whenever the {@code prod}
 * Spring profile is active; derives a deterministic dev/test fallback key only outside {@code prod}.</p>
 */
@Component
public class AiSecretCipher {

    private static final Logger log = LoggerFactory.getLogger(AiSecretCipher.class);
    private static final String PREFIX = "v1:";
    private static final String AES_ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AiSecretCipher(AiProperties aiProperties) {
        this(aiProperties, null);
    }

    @Autowired
    public AiSecretCipher(AiProperties aiProperties, Environment environment) {
        this(
                Objects.requireNonNull(aiProperties, "aiProperties must not be null").getEncryptionKey(),
                isProdProfile(environment)
        );
    }

    public AiSecretCipher(String configuredBase64Key) {
        this(configuredBase64Key, false);
    }

    private AiSecretCipher(String configuredBase64Key, boolean prodProfile) {
        byte[] keyBytes;
        if (configuredBase64Key == null || configuredBase64Key.isBlank()) {
            if (prodProfile) {
                throw new IllegalStateException("devsphere.ai.encryption-key must be configured in prod profile");
            }
            log.warn("devsphere.ai.encryption-key is blank; deriving SHA-256 fallback key for dev/test");
            keyBytes = deriveFallbackKey();
        } else {
            try {
                keyBytes = Base64.getDecoder().decode(configuredBase64Key.trim());
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("devsphere.ai.encryption-key must be valid Base64");
            }
            if (keyBytes.length != KEY_BYTES) {
                throw new IllegalStateException("devsphere.ai.encryption-key must decode to exactly 32 bytes");
            }
        }
        this.secretKey = new SecretKeySpec(keyBytes, AES_ALGORITHM);
    }

    private static boolean isProdProfile(Environment environment) {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw AiException.invalidInput("Credential plaintext must not be blank");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, payload, IV_BYTES, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt AI provider credential", ex);
        }
    }

    public String decrypt(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        String trimmed = encryptedValue.strip();
        if (!trimmed.startsWith(PREFIX)) {
            throw new AiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_SECRET_DECRYPT_FAILED",
                    "Unsupported encrypted credential format");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(trimmed.substring(PREFIX.length()));
        } catch (IllegalArgumentException ex) {
            throw new AiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_SECRET_DECRYPT_FAILED",
                    "Malformed encrypted credential");
        }
        if (payload.length <= IV_BYTES + TAG_BYTES) {
            throw new AiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_SECRET_DECRYPT_FAILED",
                    "Truncated encrypted credential");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            System.arraycopy(payload, IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new AiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI_SECRET_DECRYPT_FAILED",
                    "Failed to decrypt AI provider credential");
        }
    }

    private static byte[] deriveFallbackKey() {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest("devsphere:ai:secret-cipher:dev-fallback:v1".getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
