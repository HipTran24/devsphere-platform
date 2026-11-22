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
        assertThat(crypto.decrypt(challenge, crypto.encrypt(challenge, "000012", VerificationCodeCrypto.Purpose.RESET_PASSWORD), VerificationCodeCrypto.Purpose.RESET_PASSWORD))
                .isEqualTo("000012");
    }

    @Test
    void hashIsBoundToBothChallengeAndCode() {
        UUID challenge = UUID.randomUUID();
        String hash = crypto.hash(challenge, "004200");
        assertThat(crypto.matches(challenge, "004200", hash)).isTrue();
        assertThat(crypto.matches(challenge, "004201", hash)).isFalse();
        assertThat(crypto.matches(UUID.randomUUID(), "004200", hash)).isFalse();
        assertThat(crypto.matches(challenge, "004200", "!malformed")).isFalse();

        String recoveryHash = crypto.hash(challenge, "004200", VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        assertThat(crypto.matches(challenge, "004200", recoveryHash, VerificationCodeCrypto.Purpose.RESET_PASSWORD)).isTrue();
        assertThat(crypto.matches(challenge, "004200", hash, VerificationCodeCrypto.Purpose.RESET_PASSWORD)).isFalse();
        assertThat(crypto.matches(challenge, "004200", recoveryHash, VerificationCodeCrypto.Purpose.VERIFY_EMAIL)).isFalse();
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
    void crossPurposeCiphertextCannotBeDecryptedUnderDifferentPurpose() {
        UUID challenge = UUID.randomUUID();
        String encryptedEmail = crypto.encrypt(challenge, "123456", VerificationCodeCrypto.Purpose.VERIFY_EMAIL);
        String encryptedRecovery = crypto.encrypt(challenge, "123456", VerificationCodeCrypto.Purpose.RESET_PASSWORD);

        assertThat(crypto.decrypt(challenge, encryptedEmail, VerificationCodeCrypto.Purpose.VERIFY_EMAIL)).isEqualTo("123456");
        assertThat(crypto.decrypt(challenge, encryptedRecovery, VerificationCodeCrypto.Purpose.RESET_PASSWORD)).isEqualTo("123456");

        assertThatIllegalArgumentException().isThrownBy(() ->
                crypto.decrypt(challenge, encryptedEmail, VerificationCodeCrypto.Purpose.RESET_PASSWORD));
        assertThatIllegalArgumentException().isThrownBy(() ->
                crypto.decrypt(challenge, encryptedRecovery, VerificationCodeCrypto.Purpose.VERIFY_EMAIL));
    }

    @Test
    void legacyOverloadsPreserveVerifyEmailByteCompatibility() {
        UUID challenge = UUID.randomUUID();
        String code = "654321";
        String legacyHash = crypto.hash(challenge, code);
        String explicitHash = crypto.hash(challenge, code, VerificationCodeCrypto.Purpose.VERIFY_EMAIL);
        assertThat(legacyHash).isEqualTo(explicitHash);

        String legacyEncrypted = crypto.encrypt(challenge, code);
        assertThat(crypto.decrypt(challenge, legacyEncrypted, VerificationCodeCrypto.Purpose.VERIFY_EMAIL)).isEqualTo(code);
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
