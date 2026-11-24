package com.project.devsphere.ai.application;

import com.project.devsphere.ai.infrastructure.AiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSecretCipherTest {

    @Test
    @DisplayName("BLOCKER-3: prod profile fails fast when devsphere.ai.encryption-key is blank")
    void prodProfileRejectsBlankEncryptionKey() {
        AiProperties props = new AiProperties();
        props.setEncryptionKey("");
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        assertThatThrownBy(() -> new AiSecretCipher(props, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("devsphere.ai.encryption-key must be configured in prod profile");
    }

    @Test
    @DisplayName("BLOCKER-3: prod profile accepts valid 32-byte Base64 encryption key")
    void prodProfileAcceptsValidKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        AiProperties props = new AiProperties();
        props.setEncryptionKey(Base64.getEncoder().encodeToString(key));
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        AiSecretCipher cipher = new AiSecretCipher(props, prodEnv);
        String encrypted = cipher.encrypt("test-mock-prod-key");
        assertThat(encrypted).startsWith("v1:");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("test-mock-prod-key");
    }

    @Test
    @DisplayName("Non-prod profile derives fallback key when blank and rejects malformed Base64/lengths")
    void nonProdFallbackAndInvalidKeyValidation() {
        AiProperties blankProps = new AiProperties();
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");
        AiSecretCipher devCipher = new AiSecretCipher(blankProps, devEnv);

        String encrypted = devCipher.encrypt("test-mock-dev-key");
        assertThat(devCipher.decrypt(encrypted)).isEqualTo("test-mock-dev-key");

        AiProperties badBase64 = new AiProperties();
        badBase64.setEncryptionKey("not-valid-base64!!!");
        assertThatThrownBy(() -> new AiSecretCipher(badBase64, devEnv))
                .isInstanceOf(IllegalStateException.class);

        AiProperties shortKey = new AiProperties();
        shortKey.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> new AiSecretCipher(shortKey, devEnv))
                .isInstanceOf(IllegalStateException.class);
    }
}
