package com.project.devsphere.identity.domain;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SessionTokens {

    private static final int KEY_LENGTH = 32;
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final JOSEObjectType TYPE_AT_JWT = new JOSEObjectType("at+jwt");
    private static final String CLAIM_SID = "sid";
    private static final String CLAIM_TOKEN_USE = "token_use";
    private static final String TOKEN_USE_ACCESS = "access";

    private final byte[] key;
    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public SessionTokens(byte[] key, String issuer, String audience, Clock clock) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("key must be exactly 32 bytes");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("audience must not be blank");
        }
        this.key = key.clone();
        this.issuer = issuer;
        this.audience = audience;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.secureRandom = new SecureRandom();
    }

    public IssuedAccess issueAccess(UUID userId, UUID sessionId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");

        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(ACCESS_TTL);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(Collections.singletonList(audience))
                .subject(userId.toString())
                .claim(CLAIM_SID, sessionId.toString())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim(CLAIM_TOKEN_USE, TOKEN_USE_ACCESS)
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(TYPE_AT_JWT)
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        try {
            signedJWT.sign(new MACSigner(key));
            return new IssuedAccess(signedJWT.serialize(), expiresAt);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign access token", e);
        }
    }

    public Optional<AccessIdentity> readAccess(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }

        try {
            SignedJWT signedJWT = SignedJWT.parse(raw);
            JWSHeader header = signedJWT.getHeader();

            if (!JWSAlgorithm.HS256.equals(header.getAlgorithm())) {
                return Optional.empty();
            }
            if (header.getType() == null || !"at+jwt".equals(header.getType().getType())) {
                return Optional.empty();
            }
            if (header.getCriticalParams() != null && !header.getCriticalParams().isEmpty()) {
                return Optional.empty();
            }
            if (!signedJWT.verify(new MACVerifier(key))) {
                return Optional.empty();
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            if (!issuer.equals(claims.getIssuer())) {
                return Optional.empty();
            }
            List<String> audiences = claims.getAudience();
            if (audiences == null || audiences.size() != 1 || !audience.equals(audiences.get(0))) {
                return Optional.empty();
            }
            if (!TOKEN_USE_ACCESS.equals(claims.getStringClaim(CLAIM_TOKEN_USE))) {
                return Optional.empty();
            }

            Date iatDate = claims.getIssueTime();
            Date nbfDate = claims.getNotBeforeTime();
            Date expDate = claims.getExpirationTime();
            if (iatDate == null || nbfDate == null || expDate == null) {
                return Optional.empty();
            }

            Instant iat = iatDate.toInstant();
            Instant nbf = nbfDate.toInstant();
            Instant exp = expDate.toInstant();
            Instant now = clock.instant();

            if (iat.isAfter(nbf) || nbf.isAfter(now) || !now.isBefore(exp)) {
                return Optional.empty();
            }

            Duration lifetimeFromIat = Duration.between(iat, exp);
            if (lifetimeFromIat.isNegative() || lifetimeFromIat.isZero() || lifetimeFromIat.compareTo(ACCESS_TTL) > 0) {
                return Optional.empty();
            }

            Duration lifetimeFromNbf = Duration.between(nbf, exp);
            if (lifetimeFromNbf.isNegative() || lifetimeFromNbf.isZero() || lifetimeFromNbf.compareTo(ACCESS_TTL) > 0) {
                return Optional.empty();
            }

            String sub = claims.getSubject();
            String sid = claims.getStringClaim(CLAIM_SID);
            String jti = claims.getJWTID();
            if (sub == null || sid == null || jti == null) {
                return Optional.empty();
            }

            UUID userId = UUID.fromString(sub);
            UUID sessionId = UUID.fromString(sid);
            UUID.fromString(jti);

            return Optional.of(new AccessIdentity(userId, sessionId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public String generateRefresh() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String digestRefresh(String raw) {
        if (raw == null || raw.length() != 43) {
            throw new IllegalArgumentException("Refresh token must be exactly 43 characters");
        }

        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64url encoding", e);
        }

        if (decoded.length != 32) {
            throw new IllegalArgumentException("Refresh token must decode to exactly 32 bytes");
        }

        String reencoded = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
        if (!raw.equals(reencoded)) {
            throw new IllegalArgumentException("Refresh token is not in canonical unpadded base64url form");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public record IssuedAccess(String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedAccess[expiresAt=" + expiresAt + "]";
        }
    }

    public record AccessIdentity(UUID userId, UUID sessionId) {
    }
}
