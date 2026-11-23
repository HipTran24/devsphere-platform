package com.project.devsphere.identity.application;

import com.project.devsphere.identity.domain.SessionTokens;
import java.time.Clock;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SessionTokenConfiguration {

    @Bean
    public SessionTokens sessionTokens(
            @Value("${devsphere.auth.session.signing-key:}") String signingKey,
            @Value("${devsphere.auth.session.issuer:devsphere}") String issuer,
            @Value("${devsphere.auth.session.audience:devsphere-api}") String audience
    ) {
        if (signingKey == null || signingKey.isBlank()) {
            throw new IllegalStateException("Session token signing key must be configured via devsphere.auth.session.signing-key");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(signingKey.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Session token signing key is not valid Base64");
        }

        if (keyBytes.length != 32) {
            throw new IllegalStateException("Session token signing key must decode to exactly 32 bytes");
        }

        return new SessionTokens(keyBytes, issuer, audience, Clock.systemUTC());
    }
}
