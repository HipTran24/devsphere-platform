package com.project.devsphere.identity.domain;

import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class VerificationCodeCryptoTest {
    private final VerificationCodeCrypto crypto = new VerificationCodeCrypto(testKey(1), testKey(2));

    @Test
    void codesHaveSixAsciiDigitsIncludingLeadingZeroes() {
        for (int sample = 0; sample < 24; sample++) {
            assertThat(crypto.generateCode()).matches("[0-9]{6}");
        }
        UUID challenge = UUID.randomUUID();
        assertThat(crypto.decrypt(challenge, crypto.encrypt(challenge, "000012"))).isEqualTo("000012");
    }

    @Test
    void hashIsBoundToBothChallengeAndCode() {
        UUID challenge = UUID.randomUUID();
        String hash = crypto.hash(challenge, "004200");
        assertThat(crypto.matches(challenge, "004200", hash)).isTrue();
        assertThat(crypto.matches(challenge, "004201", hash)).isFalse();
        assertThat(crypto.matches(UUID.randomUUID(), "004200", hash)).isFalse();
        assertThat(crypto.matches(challenge, "004200", "!malformed")).isFalse();
    }

    @Test
    void rejectsTamperingAndCiphertextFromAnotherChallenge() {
        UUID challenge = UUID.randomUUID();
        String encrypted = crypto.encrypt(challenge, "765432");
        assertThat(crypto.encrypt(challenge, "765432")).isNotEqualTo(encrypted);
        assertThat(crypto.decrypt(challenge, encrypted)).isEqualTo("765432");
        assertThatIllegalArgumentException().isThrownBy(() -> crypto.decrypt(UUID.randomUUID(), encrypted));

        byte[] tampered = Base64.getUrlDecoder().decode(encrypted);
        tampered[tampered.length - 1] ^= 1;
        String invalid = Base64.getUrlEncoder().withoutPadding().encodeToString(tampered);
        assertThatIllegalArgumentException().isThrownBy(() -> crypto.decrypt(challenge, invalid));
    }

    @Test
    void copiesKeysAndRejectsReusingOneKeyForBothOperations() {
        byte[] mac = testKey(1);
        byte[] cipher = testKey(2);
        VerificationCodeCrypto instance = new VerificationCodeCrypto(mac, cipher);
        UUID challenge = UUID.randomUUID();
        String hash = instance.hash(challenge, "000001");
        String encrypted = instance.encrypt(challenge, "000001");
        Arrays.fill(mac, (byte) 0);
        Arrays.fill(cipher, (byte) 0);
        assertThat(instance.matches(challenge, "000001", hash)).isTrue();
        assertThat(instance.decrypt(challenge, encrypted)).isEqualTo("000001");
        assertThatIllegalArgumentException().isThrownBy(() -> new VerificationCodeCrypto(testKey(1), testKey(1)));
    }

    private static byte[] testKey(int marker) {
        byte[] key = new byte[32];
        key[0] = (byte) marker;
        return key;
    }
}
