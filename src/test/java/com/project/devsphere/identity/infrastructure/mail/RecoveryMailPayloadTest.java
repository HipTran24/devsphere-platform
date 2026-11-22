package com.project.devsphere.identity.infrastructure.mail;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryMailPayloadTest {

    private static final String VALID_CANONICAL_UUID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
    // 34 bytes = 12 IV + 16 GCM tag + 6 code, encoded in unpadded Base64URL -> 46 characters
    private static final String VALID_CIPHERTEXT = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrsg";

    @Test
    void validPayloadParsesSuccessfully() {
        String json = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                VALID_CANONICAL_UUID, VALID_CIPHERTEXT);

        RecoveryMailPayload payload = RecoveryMailPayload.parse(json, VALID_CANONICAL_UUID);

        assertThat(payload.schemaVersion()).isEqualTo(1);
        assertThat(payload.challengeId()).isEqualTo(UUID.fromString(VALID_CANONICAL_UUID));
        assertThat(payload.challengeString()).isEqualTo(VALID_CANONICAL_UUID);
        assertThat(payload.ciphertext()).isEqualTo(VALID_CIPHERTEXT);
        assertThat(payload.rawJson()).isEqualTo(json);
    }

    @Test
    void toStringRedactsCiphertextAndRawJson() {
        String json = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                VALID_CANONICAL_UUID, VALID_CIPHERTEXT);

        RecoveryMailPayload payload = RecoveryMailPayload.parse(json, VALID_CANONICAL_UUID);
        String diagnostic = payload.toString();

        assertThat(diagnostic).doesNotContain(VALID_CIPHERTEXT);
        assertThat(diagnostic).doesNotContain(json);
        assertThat(diagnostic).contains("[REDACTED]");
        assertThat(diagnostic).contains(VALID_CANONICAL_UUID);
        assertThat(diagnostic).contains("schemaVersion=1");
    }

    @Test
    void oneArgumentShapeOnlyParseSucceedsWithoutIdempotencyKey() {
        String json = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                VALID_CANONICAL_UUID, VALID_CIPHERTEXT);

        RecoveryMailPayload payload = RecoveryMailPayload.parse(json);

        assertThat(payload.schemaVersion()).isEqualTo(1);
        assertThat(payload.challengeId()).isEqualTo(UUID.fromString(VALID_CANONICAL_UUID));
        assertThat(payload.challengeString()).isEqualTo(VALID_CANONICAL_UUID);
        assertThat(payload.ciphertext()).isEqualTo(VALID_CIPHERTEXT);
        assertThat(payload.rawJson()).isEqualTo(json);
    }

    @Test
    void twoArgumentParseRejectsNullBlankNoncanonicalOrMismatchedIdempotencyKey() {
        String json = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                VALID_CANONICAL_UUID, VALID_CIPHERTEXT);

        // Null idempotency key
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(json, null));

        // Empty or blank idempotency key
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(json, ""));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(json, "   "));

        // Non-canonical UUID idempotency key (uppercase)
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(json, VALID_CANONICAL_UUID.toUpperCase()));

        // Malformed / non-UUID idempotency key
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(json, "not-a-uuid"));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(json, "{" + VALID_CANONICAL_UUID + "}"));

        // Mismatched idempotency key
        String differentIdempotency = UUID.randomUUID().toString();
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(json, differentIdempotency));
    }

    @Test
    void rejectsNonObjectOrMalformedJson() {
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(null));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(""));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse("   "));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse("{invalid-json"));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse("[1, 2, 3]"));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse("\"a string\""));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse("12345"));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse("true"));
    }

    @Test
    void rejectsMissingOrExtraFields() {
        // Missing schemaVersion
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}", VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));

        // Missing challengeId
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"ciphertext\":\"%s\"}", VALID_CIPHERTEXT)
        ));

        // Missing ciphertext
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\"}", VALID_CANONICAL_UUID)
        ));

        // Extra field
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\",\"extra\":123}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));

        // 3 fields but unexpected field name
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"other\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));
    }

    @Test
    void rejectsInvalidSchemaVersionTypesAndValues() {
        // String "1"
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":\"1\",\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));

        // Null
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":null,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));

        // Boolean
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":true,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));

        // Fractional float/double
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1.5,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1.0,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));

        // Array
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":[1],\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));

        // Unsupported integers
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":0,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":2,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":-1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));
    }

    @Test
    void rejectsInvalidChallengeId() {
        // Null
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":null,\"ciphertext\":\"%s\"}", VALID_CIPHERTEXT)
        ));

        // Non-textual integer
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":12345,\"ciphertext\":\"%s\"}", VALID_CIPHERTEXT)
        ));

        // Uppercase UUID (non-canonical)
        String uppercaseUuid = VALID_CANONICAL_UUID.toUpperCase();
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        uppercaseUuid, VALID_CIPHERTEXT)
        ));

        // Non-UUID invalid length or characters
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"not-a-uuid\",\"ciphertext\":\"%s\"}", VALID_CIPHERTEXT)
        ));

        // Braced UUID
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"{%s}\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT)
        ));

        // Mismatched idempotency key
        String differentIdempotency = UUID.randomUUID().toString();
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT),
                differentIdempotency
        ));
    }

    @Test
    void rejectsInvalidCiphertext() {
        // Null
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":null}", VALID_CANONICAL_UUID)
        ));

        // Non-string
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":123}", VALID_CANONICAL_UUID)
        ));

        // Empty string
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"\"}", VALID_CANONICAL_UUID)
        ));

        // Whitespace
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"   \"}", VALID_CANONICAL_UUID)
        ));

        // Non-base64url characters
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"!@#$%%^&*()_+\"}", VALID_CANONICAL_UUID)
        ));

        // Standard base64 with '+' or '/'
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, "A+".repeat(23))
        ));

        // Base64 with padding '='
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s=\"}",
                        VALID_CANONICAL_UUID, VALID_CIPHERTEXT.substring(0, 45))
        ));

        // Non-canonical trailing bits (34 bytes decoded, valid chars, but trailing bits not 0)
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrst")
        ));

        // Too short (< 34 bytes decoded)
        byte[] shortBytes = new byte[33];
        String shortEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(shortBytes);
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, shortEncoded)
        ));

        // Too long (> 128 bytes decoded)
        byte[] longBytes = new byte[129];
        String longEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(longBytes);
        assertThatIllegalArgumentException().isThrownBy(() -> RecoveryMailPayload.parse(
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                        VALID_CANONICAL_UUID, longEncoded)
        ));
    }

    @Test
    void malformedJsonDoesNotLeakPayloadOrCausesIntoExceptionChain() {
        String sentinelSecret = "LEAK_SENTINEL_CIPHERTEXT_OR_CREDENTIAL_987654";
        String adversarialJson = "{\"schemaVersion\":1,\"ciphertext\":\"" + sentinelSecret + "\", malformed}";

        assertThatThrownBy(() -> RecoveryMailPayload.parse(adversarialJson))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(throwable -> {
                    assertThat(throwable.getCause()).isNull();
                    StringWriter sw = new StringWriter();
                    throwable.printStackTrace(new PrintWriter(sw));
                    String rendered = sw.toString();
                    assertThat(rendered).doesNotContain(sentinelSecret);
                });
    }
}
