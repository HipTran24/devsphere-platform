package com.project.devsphere.identity.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PasswordResetGrantTokenTest {

    @Test
    void generatesCanonical43CharacterGrantAndMatchingDigest() {
        for (int i = 0; i < 20; i++) {
            PasswordResetGrantToken token = PasswordResetGrantToken.generate();
            assertThat(token.rawGrant()).hasSize(43).matches("^[A-Za-z0-9_-]{43}$");
            assertThat(token.digest()).hasSize(64).matches("^[0-9a-f]{64}$");

            byte[] decoded = Base64.getUrlDecoder().decode(token.rawGrant());
            assertThat(decoded).hasSize(32);

            String recomputedDigest = HexFormat.of().formatHex(
                    sha256(decoded)
            );
            assertThat(token.digest()).isEqualTo(recomputedDigest);
            assertThat(PasswordResetGrantToken.digest(token.rawGrant())).isEqualTo(token.digest());
        }
    }

    @Test
    void rejectsInvalidLength() {
        assertThatIllegalArgumentException().isThrownBy(() -> PasswordResetGrantToken.digest(null));
        assertThatIllegalArgumentException().isThrownBy(() -> PasswordResetGrantToken.digest(""));
        assertThatIllegalArgumentException().isThrownBy(() -> PasswordResetGrantToken.digest("a".repeat(42)));
        assertThatIllegalArgumentException().isThrownBy(() -> PasswordResetGrantToken.digest("a".repeat(44)));
    }

    @Test
    void rejectsInvalidCharacters() {
        // '+' or '/' or '=' or spaces are not allowed
        String grant = PasswordResetGrantToken.generate().rawGrant();
        String withPlus = "+" + grant.substring(1);
        String withSlash = "/" + grant.substring(1);
        String withSpace = " " + grant.substring(1);
        String withPadding = grant.substring(0, 42) + "=";

        assertThatIllegalArgumentException().isThrownBy(() -> PasswordResetGrantToken.digest(withPlus));
        assertThatIllegalArgumentException().isThrownBy(() -> PasswordResetGrantToken.digest(withSlash));
        assertThatIllegalArgumentException().isThrownBy(() -> PasswordResetGrantToken.digest(withSpace));
        assertThatIllegalArgumentException().isThrownBy(() -> PasswordResetGrantToken.digest(withPadding));
    }

    @Test
    void rejectsNonCanonicalTrailingBits() {
        byte[] raw = new byte[32];
        String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        assertThat(canonical).hasSize(43);
        // The 43rd character has 4 data bits and 2 zero bits. If we modify the 43rd character
        // to set those 2 lowest bits, decoding might yield the same 32 bytes, but re-encoding
        // will not match, failing the strict canonical check!
        char lastChar = canonical.charAt(42);
        // Base64URL char for index 0 is 'A' (bits 000000). Index 1 is 'B' (bits 000001, lowest bit set).
        char nonCanonicalLastChar = (lastChar == 'A') ? 'B' : 'A';
        String nonCanonicalGrant = canonical.substring(0, 42) + nonCanonicalLastChar;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PasswordResetGrantToken.digest(nonCanonicalGrant));
    }

    @Test
    void toStringRedactsSecret() {
        PasswordResetGrantToken token = PasswordResetGrantToken.generate();
        assertThat(token.toString()).isEqualTo("PasswordResetGrantToken[redacted]");
        assertThat(token.toString()).doesNotContain(token.rawGrant());
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
