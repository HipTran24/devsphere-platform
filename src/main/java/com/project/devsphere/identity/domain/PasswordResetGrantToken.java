package com.project.devsphere.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

public record PasswordResetGrantToken(String rawGrant, String digest) {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    public static final int RAW_BYTES_LENGTH = 32;
    public static final int ENCODED_LENGTH = 43;

    public PasswordResetGrantToken {
        Objects.requireNonNull(rawGrant, "rawGrant must not be null");
        Objects.requireNonNull(digest, "digest must not be null");
    }

    public static PasswordResetGrantToken generate() {
        byte[] raw = new byte[RAW_BYTES_LENGTH];
        SECURE_RANDOM.nextBytes(raw);
        String rawGrant = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String digest = hash(raw);
        return new PasswordResetGrantToken(rawGrant, digest);
    }

    public static String digest(String grant) {
        if (grant == null || grant.length() != ENCODED_LENGTH) {
            throw new IllegalArgumentException("Invalid grant length");
        }
        for (int i = 0; i < ENCODED_LENGTH; i++) {
            char c = grant.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                throw new IllegalArgumentException("Invalid grant characters");
            }
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(grant);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Base64URL grant", e);
        }
        if (decoded.length != RAW_BYTES_LENGTH) {
            throw new IllegalArgumentException("Invalid decoded grant length");
        }
        // Strict canonical check: re-encoding decoded bytes must match input string exactly
        String reencoded = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
        if (!reencoded.equals(grant)) {
            throw new IllegalArgumentException("Non-canonical Base64URL grant encoding");
        }
        return hash(decoded);
    }

    private static String hash(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    @Override
    public String toString() {
        return "PasswordResetGrantToken[redacted]";
    }
}
