package com.project.devsphere.identity.infrastructure.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record RecoveryMailPayload(
        int schemaVersion,
        UUID challengeId,
        String challengeString,
        String ciphertext,
        String rawJson
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern CANONICAL_UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern BASE64URL_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+$");

    public static final int EXPECTED_SCHEMA_VERSION = 1;
    public static final int MIN_DECODED_CIPHERTEXT_BYTES = 34; // 12 IV + 16 GCM tag + 6 code
    public static final int MAX_DECODED_CIPHERTEXT_BYTES = 128;
    public static final int MIN_CIPHERTEXT_CHARS = 46;
    public static final int MAX_CIPHERTEXT_CHARS = 256;

    public RecoveryMailPayload {
        Objects.requireNonNull(challengeId, "challengeId must not be null");
        Objects.requireNonNull(challengeString, "challengeString must not be null");
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
        Objects.requireNonNull(rawJson, "rawJson must not be null");
    }

    @Override
    public String toString() {
        return "RecoveryMailPayload[schemaVersion=" + schemaVersion
                + ", challengeId=" + challengeId
                + ", challengeString=" + challengeString
                + ", ciphertext=[REDACTED], rawJson=[REDACTED]]";
    }

    public static RecoveryMailPayload parse(String rawJson) {
        return parseInternal(rawJson, null, false);
    }

    public static RecoveryMailPayload parse(String rawJson, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be null or blank");
        }
        if (idempotencyKey.length() != 36 || !CANONICAL_UUID_PATTERN.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("idempotencyKey must be a canonical lowercase UUID string");
        }
        return parseInternal(rawJson, idempotencyKey, true);
    }

    private static RecoveryMailPayload parseInternal(String rawJson, String idempotencyKey, boolean enforceIdempotency) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("Payload cannot be null or blank");
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON payload");
        }

        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Payload must be a JSON object");
        }

        if (root.size() != 3) {
            throw new IllegalArgumentException("Payload must contain exactly three fields");
        }

        if (!root.has("schemaVersion") || !root.has("challengeId") || !root.has("ciphertext")) {
            throw new IllegalArgumentException("Payload missing required fields");
        }

        JsonNode versionNode = root.get("schemaVersion");
        if (versionNode == null || versionNode.isFloatingPointNumber() || !versionNode.isIntegralNumber()
                || !versionNode.canConvertToInt() || versionNode.intValue() != EXPECTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be numeric integer 1");
        }
        int version = versionNode.intValue();

        JsonNode challengeNode = root.get("challengeId");
        if (challengeNode == null || !challengeNode.isTextual()) {
            throw new IllegalArgumentException("challengeId must be a text string");
        }
        String challengeStr = challengeNode.textValue();
        if (challengeStr.length() != 36 || !CANONICAL_UUID_PATTERN.matcher(challengeStr).matches()) {
            throw new IllegalArgumentException("challengeId must be a canonical lowercase UUID string");
        }

        UUID challengeUuid;
        try {
            challengeUuid = UUID.fromString(challengeStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("challengeId is not a valid UUID");
        }
        if (!challengeUuid.toString().equals(challengeStr)) {
            throw new IllegalArgumentException("challengeId must be canonical lowercase UUID");
        }

        if (enforceIdempotency && !challengeStr.equals(idempotencyKey)) {
            throw new IllegalArgumentException("challengeId does not match idempotency key");
        }

        JsonNode ciphertextNode = root.get("ciphertext");
        if (ciphertextNode == null || !ciphertextNode.isTextual()) {
            throw new IllegalArgumentException("ciphertext must be a text string");
        }
        String cipherStr = ciphertextNode.textValue();
        if (cipherStr.isEmpty()) {
            throw new IllegalArgumentException("ciphertext must not be empty");
        }
        if (cipherStr.length() < MIN_CIPHERTEXT_CHARS || cipherStr.length() > MAX_CIPHERTEXT_CHARS) {
            throw new IllegalArgumentException("ciphertext length out of bounds");
        }
        if (!BASE64URL_PATTERN.matcher(cipherStr).matches()) {
            throw new IllegalArgumentException("ciphertext contains invalid characters");
        }

        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(cipherStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ciphertext is not valid Base64URL");
        }

        if (decoded.length < MIN_DECODED_CIPHERTEXT_BYTES || decoded.length > MAX_DECODED_CIPHERTEXT_BYTES) {
            throw new IllegalArgumentException("ciphertext decoded length out of bounds");
        }

        String reencoded = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
        if (!reencoded.equals(cipherStr)) {
            throw new IllegalArgumentException("ciphertext must be canonical unpadded Base64URL");
        }

        return new RecoveryMailPayload(version, challengeUuid, challengeStr, cipherStr, rawJson);
    }
}
