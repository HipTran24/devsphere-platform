package com.project.devsphere.gitintegration.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Signs short-lived RS256 GitHub App JWTs using standard JCA {@code SHA256withRSA}
 * and PKCS#8 RSA PEM private keys (zero extra Maven dependencies).
 *
 * <p>Never logs raw PEM private keys or generated JWT strings.
 */
@Component
public class GitHubAppJwtSigner {

    private static final String BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----";
    private static final String END_PRIVATE_KEY = "-----END PRIVATE KEY-----";
    private static final long CLOCK_DRIFT_PAST_SECONDS = 60L;
    private static final long EXPIRATION_FUTURE_SECONDS = 9L * 60L; // 9 minutes (GitHub max is 10m)

    private final GitHubAppProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    private volatile CachedKey cachedKey;

    @Autowired
    public GitHubAppJwtSigner(
            GitHubAppProperties properties,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this(properties, Clock.systemUTC(), objectMapper);
    }

    public GitHubAppJwtSigner(GitHubAppProperties properties, Clock clock) {
        this(properties, clock, null);
    }

    public GitHubAppJwtSigner(GitHubAppProperties properties, Clock clock, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Generates a freshly signed RS256 GitHub App JWT from the configured {@link GitHubAppProperties}.
     */
    public String generateAppJwt() {
        String appId = properties.getAppId();
        String pem = properties.getPrivateKeyPem();
        if (appId == null || appId.isBlank()) {
            throw new GitHubApiUnavailableException("GitHub App ID is not configured");
        }
        if (pem == null || pem.isBlank()) {
            throw new GitHubApiUnavailableException("GitHub App private key is not configured");
        }
        RSAPrivateKey privateKey = resolveCachedPrivateKey(pem);
        return signJwt(appId.trim(), privateKey, clock.instant());
    }

    /**
     * Generates an RS256 GitHub App JWT for an explicit App ID and PKCS#8 RSA PEM private key.
     */
    public String generateAppJwt(String appId, String privateKeyPem) {
        if (appId == null || appId.isBlank()) {
            throw new GitHubApiUnavailableException("GitHub App ID must not be blank");
        }
        RSAPrivateKey privateKey = parsePkcs8RsaPrivateKey(privateKeyPem);
        return signJwt(appId.trim(), privateKey, clock.instant());
    }

    /**
     * Parses a PKCS#8 RSA PEM private key, handling both literal {@code \n} escapes and
     * newline-separated PEM formatting.
     */
    public static RSAPrivateKey parsePkcs8RsaPrivateKey(String rawPem) {
        if (rawPem == null || rawPem.isBlank()) {
            throw new GitHubApiUnavailableException("GitHub App private key PEM must not be blank");
        }

        // Normalize literal "\n" / "\r\n" sequences commonly injected via environment variables.
        String normalized = rawPem.strip()
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n");

        int beginIdx = normalized.indexOf(BEGIN_PRIVATE_KEY);
        int endIdx = normalized.indexOf(END_PRIVATE_KEY);
        if (beginIdx < 0 || endIdx <= beginIdx) {
            throw new GitHubApiUnavailableException("GitHub App private key must be a valid PKCS#8 PEM block");
        }

        String base64Body = normalized.substring(beginIdx + BEGIN_PRIVATE_KEY.length(), endIdx)
                .replaceAll("\\s+", "");
        if (base64Body.isEmpty()) {
            throw new GitHubApiUnavailableException("GitHub App private key PEM body is empty");
        }

        byte[] derBytes;
        try {
            derBytes = Base64.getDecoder().decode(base64Body);
        } catch (IllegalArgumentException ex) {
            throw new GitHubApiUnavailableException("GitHub App private key PEM contains invalid Base64");
        }

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(derBytes));
            if (!(key instanceof RSAPrivateKey rsaKey)) {
                throw new GitHubApiUnavailableException("GitHub App private key is not an RSA key");
            }
            return rsaKey;
        } catch (GeneralSecurityException ex) {
            throw new GitHubApiUnavailableException("Failed to parse PKCS#8 RSA private key for GitHub App");
        }
    }

    private RSAPrivateKey resolveCachedPrivateKey(String pem) {
        String digest = sha256Hex(pem);
        CachedKey current = cachedKey;
        if (current != null && current.pemSha256().equals(digest)) {
            return current.privateKey();
        }
        RSAPrivateKey parsed = parsePkcs8RsaPrivateKey(pem);
        cachedKey = new CachedKey(digest, parsed);
        return parsed;
    }

    private String signJwt(String appId, RSAPrivateKey privateKey, Instant now) {
        long nowEpoch = now.getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iat", nowEpoch - CLOCK_DRIFT_PAST_SECONDS);
        claims.put("exp", nowEpoch + EXPIRATION_FUTURE_SECONDS);
        claims.put("iss", appId);

        Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();
        String encodedHeader;
        String encodedPayload;
        try {
            encodedHeader = urlEncoder.encodeToString(objectMapper.writeValueAsBytes(header));
            encodedPayload = urlEncoder.encodeToString(objectMapper.writeValueAsBytes(claims));
        } catch (Exception ex) {
            throw new GitHubApiUnavailableException("Failed to serialize GitHub App JWT claims", ex);
        }

        String signingInput = encodedHeader + "." + encodedPayload;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String encodedSignature = urlEncoder.encodeToString(signature.sign());
            return signingInput + "." + encodedSignature;
        } catch (GeneralSecurityException ex) {
            throw new GitHubApiUnavailableException("Failed to sign GitHub App RS256 JWT");
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private record CachedKey(String pemSha256, RSAPrivateKey privateKey) {
    }
}
