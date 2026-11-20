package com.project.devsphere.identity.api;

import com.project.devsphere.identity.domain.VerificationCodeCrypto;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class RegistrationConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean
    public VerificationCodeCrypto verificationCodeCrypto(
            @Value("${devsphere.auth.otp.hmac-key:#{null}}") String hmacKeyProperty,
            @Value("${devsphere.auth.otp.encryption-key:#{null}}") String encKeyProperty
    ) {
        boolean hmacBlank = hmacKeyProperty == null || hmacKeyProperty.isBlank();
        boolean encBlank = encKeyProperty == null || encKeyProperty.isBlank();

        if (hmacBlank && encBlank) {
            return null;
        }

        if (hmacBlank || encBlank) {
            throw new IllegalStateException("Both devsphere.auth.otp.hmac-key and devsphere.auth.otp.encryption-key must be configured together");
        }

        byte[] hmacKey;
        byte[] encKey;
        try {
            hmacKey = Base64.getDecoder().decode(hmacKeyProperty.trim());
            encKey = Base64.getDecoder().decode(encKeyProperty.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("OTP crypto keys must be valid Base64 encoded strings");
        }

        if (hmacKey.length != 32 || encKey.length != 32) {
            throw new IllegalStateException("OTP crypto keys must both be exactly 32 bytes");
        }

        if (MessageDigest.isEqual(hmacKey, encKey)) {
            throw new IllegalStateException("OTP HMAC key and encryption key must be distinct");
        }

        return new VerificationCodeCrypto(hmacKey, encKey);
    }
    @Bean
    public org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer strictStringCoercionCustomizer() {
        return builder -> builder.withCoercionConfig(String.class, cfg -> cfg
            .setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Integer, tools.jackson.databind.cfg.CoercionAction.Fail)
            .setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Float, tools.jackson.databind.cfg.CoercionAction.Fail)
            .setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Boolean, tools.jackson.databind.cfg.CoercionAction.Fail));
    }
}
