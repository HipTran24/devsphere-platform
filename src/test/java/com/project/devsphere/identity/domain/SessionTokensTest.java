package com.project.devsphere.identity.domain;

import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SessionTokensTest {
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final String ISSUER = "devsphere-test";
    private static final String AUDIENCE = "devsphere-api";
    private static final UUID USER = UUID.fromString("686429cf-239a-4f6f-9782-c78d32f9a59e");
    private static final UUID SESSION = UUID.fromString("72d9e21c-c59a-48c5-8e37-e018f3be18fe");

    @Test
    void issuesSignedAccessWithIdentityAndFixedLifetime() throws Exception {
        SessionTokens tokens = tokens(1, ISSUER, AUDIENCE, NOW);
        SessionTokens.IssuedAccess access = tokens.issueAccess(USER, SESSION);

        assertThat(access.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(tokens.readAccess(access.value())).contains(new SessionTokens.AccessIdentity(USER, SESSION));
        assertThat(SignedJWT.parse(access.value()).getJWTClaimsSet().getJWTID()).isNotBlank();
        assertThat(access.toString()).doesNotContain(access.value());
    }

    @Test
    void rejectsWrongKeyAudienceIssuerAndDamagedOrMalformedToken() {
        SessionTokens owner = tokens(1, ISSUER, AUDIENCE, NOW);
        String issued = owner.issueAccess(USER, SESSION).value();
        assertThat(tokens(2, ISSUER, AUDIENCE, NOW).readAccess(issued)).isEmpty();
        assertThat(tokens(1, ISSUER, "different-api", NOW).readAccess(issued)).isEmpty();
        assertThat(tokens(1, "different-issuer", AUDIENCE, NOW).readAccess(issued)).isEmpty();
        assertThat(owner.readAccess("invalid")).isEmpty();
        String[] parts = issued.split("\\.");
        char replacement = parts[2].startsWith("A") ? 'B' : 'A';
        String damaged = parts[0] + "." + parts[1] + "." + replacement + parts[2].substring(1);
        assertThat(owner.readAccess(damaged)).isEmpty();
    }

    @Test
    void acceptsAtIssuanceButRejectsBeforeAndAtExpiration() {
        String token = tokens(1, ISSUER, AUDIENCE, NOW).issueAccess(USER, SESSION).value();
        assertThat(tokens(1, ISSUER, AUDIENCE, NOW.minusSeconds(1)).readAccess(token)).isEmpty();
        assertThat(tokens(1, ISSUER, AUDIENCE, NOW.plusSeconds(899)).readAccess(token)).isPresent();
        assertThat(tokens(1, ISSUER, AUDIENCE, NOW.plusSeconds(900)).readAccess(token)).isEmpty();
    }

    @Test
    void rejectsMutationOfCallerKeyAndInvalidKeyLength() {
        byte[] key = key(1);
        SessionTokens holder = new SessionTokens(key, ISSUER, AUDIENCE, Clock.fixed(NOW, ZoneOffset.UTC));
        Arrays.fill(key, (byte) 0);

        String issued = holder.issueAccess(USER, SESSION).value();
        assertThat(tokens(1, ISSUER, AUDIENCE, NOW).readAccess(issued)).isPresent();
        assertThatIllegalArgumentException().isThrownBy(() ->
                new SessionTokens(new byte[31], ISSUER, AUDIENCE, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void generatesCanonicalOpaqueRefreshValuesAndStableDigests() {
        SessionTokens tokens = tokens(1, ISSUER, AUDIENCE, NOW);
        String raw = tokens.generateRefresh();
        String next = tokens.generateRefresh();

        assertThat(raw).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
        assertThat(raw).isNotEqualTo(next);
        assertThat(tokens.digestRefresh(raw)).matches("[0-9a-f]{64}").isEqualTo(tokens.digestRefresh(raw));
        assertThat(tokens.digestRefresh(next)).isNotEqualTo(tokens.digestRefresh(raw));
        assertThatIllegalArgumentException().isThrownBy(() -> tokens.digestRefresh(raw + "="));
        assertThatIllegalArgumentException().isThrownBy(() -> tokens.digestRefresh(" ".repeat(43)));
    }

    private static SessionTokens tokens(int value, String issuer, String audience, Instant now) {
        return new SessionTokens(key(value), issuer, audience, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static byte[] key(int marker) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) marker;
        return bytes;
    }
}
