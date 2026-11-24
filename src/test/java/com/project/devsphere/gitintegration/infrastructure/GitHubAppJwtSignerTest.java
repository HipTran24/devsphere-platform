package com.project.devsphere.gitintegration.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubAppJwtSignerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static KeyPair rsaKeyPair;
    private static String pkcs8PemMultiline;
    private static String pkcs8PemEscapedNewlines;

    @BeforeAll
    static void generateEphemeralKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        rsaKeyPair = generator.generateKeyPair();

        String base64Key = Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(rsaKeyPair.getPrivate().getEncoded());
        pkcs8PemMultiline = "-----BEGIN PRIVATE KEY-----\n" + base64Key + "\n-----END PRIVATE KEY-----";
        pkcs8PemEscapedNewlines = pkcs8PemMultiline.replace("\n", "\\n");
    }

    @Test
    @DisplayName("Signs verifiable RS256 JWT with iss, iat=now-60s, exp=now+9m from multiline PEM")
    void generateAppJwt_multilinePem_validRs256SignatureAndClaims() throws Exception {
        Instant fixedNow = Instant.parse("2026-11-24T12:00:00Z");
        Clock fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);

        GitHubAppProperties props = new GitHubAppProperties();
        props.setEnabled(true);
        props.setAppId("987654");
        props.setPrivateKeyPem(pkcs8PemMultiline);

        GitHubAppJwtSigner signer = new GitHubAppJwtSigner(props, fixedClock);
        String jwt = signer.generateAppJwt();

        verifyRs256Jwt(jwt, rsaKeyPair.getPublic(), "987654", fixedNow);
    }

    @Test
    @DisplayName("Handles literal \\n-escaped PKCS#8 PEM from environment variables")
    void generateAppJwt_escapedNewlinesPem_validRs256Signature() throws Exception {
        Instant fixedNow = Instant.parse("2026-11-24T15:30:00Z");
        Clock fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);

        GitHubAppProperties props = new GitHubAppProperties();
        props.setEnabled(true);
        props.setAppId("123456");
        props.setPrivateKeyPem(pkcs8PemEscapedNewlines);

        GitHubAppJwtSigner signer = new GitHubAppJwtSigner(props, fixedClock);
        String jwt = signer.generateAppJwt();

        verifyRs256Jwt(jwt, rsaKeyPair.getPublic(), "123456", fixedNow);
    }

    @Test
    @DisplayName("Throws GitHubApiUnavailableException when appId or PEM is missing or malformed without leaking key")
    void generateAppJwt_invalidConfig_throwsUnavailableWithoutLeakingSecret() {
        GitHubAppProperties props = new GitHubAppProperties();
        GitHubAppJwtSigner signer = new GitHubAppJwtSigner(props, Clock.systemUTC());

        assertThatThrownBy(signer::generateAppJwt)
                .isInstanceOf(GitHubApiUnavailableException.class)
                .hasMessageContaining("App ID");

        props.setAppId("42");
        assertThatThrownBy(signer::generateAppJwt)
                .isInstanceOf(GitHubApiUnavailableException.class)
                .hasMessageContaining("private key");

        String invalidPemBody = "INVALID_TEST_PEM_MATERIAL_DO_NOT_LOG";
        props.setPrivateKeyPem("-----BEGIN PRIVATE KEY-----\n" + invalidPemBody + "\n-----END PRIVATE KEY-----");
        assertThatThrownBy(signer::generateAppJwt)
                .isInstanceOf(GitHubApiUnavailableException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(invalidPemBody));
    }

    @Test
    @DisplayName("GitHubAppProperties.toString omits privateKeyPem")
    void propertiesToString_neverContainsPrivateKeyPem() {
        GitHubAppProperties props = new GitHubAppProperties();
        props.setEnabled(true);
        props.setAppId("555");
        props.setPrivateKeyPem(pkcs8PemMultiline);

        String rendered = props.toString();
        assertThat(rendered).contains("privateKeyConfigured=true");
        assertThat(rendered).doesNotContain("BEGIN PRIVATE KEY");
        assertThat(rendered).doesNotContain(pkcs8PemMultiline.substring(30, 60));
    }

    static void verifyRs256Jwt(String jwt, PublicKey publicKey, String expectedAppId, Instant expectedNow) throws Exception {
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        Base64.Decoder urlDecoder = Base64.getUrlDecoder();
        Map<String, Object> header = JSON.readValue(urlDecoder.decode(parts[0]), new TypeReference<>() {});
        Map<String, Object> payload = JSON.readValue(urlDecoder.decode(parts[1]), new TypeReference<>() {});

        assertThat(header.get("alg")).isEqualTo("RS256");
        assertThat(header.get("typ")).isEqualTo("JWT");
        assertThat(payload.get("iss")).isEqualTo(expectedAppId);
        assertThat(((Number) payload.get("iat")).longValue()).isEqualTo(expectedNow.getEpochSecond() - 60L);
        assertThat(((Number) payload.get("exp")).longValue()).isEqualTo(expectedNow.getEpochSecond() + 540L);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(urlDecoder.decode(parts[2]))).isTrue();
    }
}
