package com.project.devsphere.identity.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class VerificationCodeCrypto {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String AES_ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte[] PURPOSE = "VERIFY_EMAIL".getBytes(StandardCharsets.UTF_8);

    private static final int KEY_LENGTH_BYTES = 32;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8;
    private static final int CODE_LENGTH = 6;

    private final byte[] hmacKey;
    private final byte[] encryptionKey;
    private final SecureRandom secureRandom;

    public VerificationCodeCrypto(byte[] hmacKey, byte[] encryptionKey) {
        if (hmacKey == null || encryptionKey == null) {
            throw new IllegalArgumentException("Keys cannot be null");
        }
        if (hmacKey.length != KEY_LENGTH_BYTES || encryptionKey.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("Keys must be 32 bytes long");
        }
        if (MessageDigest.isEqual(hmacKey, encryptionKey)) {
            throw new IllegalArgumentException("HMAC key and encryption key must be distinct");
        }
        this.hmacKey = hmacKey.clone();
        this.encryptionKey = encryptionKey.clone();
        this.secureRandom = new SecureRandom();
    }

    public String generateCode() {
        int number = this.secureRandom.nextInt(1_000_000);
        return String.format(Locale.ROOT, "%06d", number);
    }

    public String hash(UUID id, String code) {
        validateId(id);
        validateCode(code);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(this.hmacKey, HMAC_ALGORITHM));
            mac.update(buildAad(id));
            byte[] rawHmac = mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to calculate HMAC", e);
        }
    }

    public boolean matches(UUID id, String code, String storedHash) {
        if (id == null || code == null || storedHash == null || !isValidCode(code)) {
            return false;
        }
        try {
            byte[] expected = Base64.getUrlDecoder().decode(storedHash);
            byte[] actual = Base64.getUrlDecoder().decode(hash(id, code));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return false;
        }
    }

    public String encrypt(UUID id, String code) {
        validateId(id);
        validateCode(code);
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            this.secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            SecretKey key = new SecretKeySpec(this.encryptionKey, AES_ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);
            cipher.updateAAD(buildAad(id));

            byte[] plaintext = code.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);

            return Base64.getUrlEncoder().withoutPadding().encodeToString(result);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt verification code", e);
        }
    }

    public String decrypt(UUID id, String ciphertext) {
        validateId(id);
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("Invalid ciphertext");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(ciphertext);
            if (decoded.length < GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES + CODE_LENGTH) {
                throw new IllegalArgumentException("Invalid ciphertext length");
            }

            byte[] iv = Arrays.copyOfRange(decoded, 0, GCM_IV_LENGTH_BYTES);
            byte[] cipherBytes = Arrays.copyOfRange(decoded, GCM_IV_LENGTH_BYTES, decoded.length);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            SecretKey key = new SecretKeySpec(this.encryptionKey, AES_ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
            cipher.updateAAD(buildAad(id));

            byte[] plaintext = cipher.doFinal(cipherBytes);
            String code = new String(plaintext, StandardCharsets.UTF_8);

            if (!isValidCode(code)) {
                throw new IllegalArgumentException("Decrypted payload is not a valid verification code");
            }
            return code;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Failed to decrypt or authenticate verification code");
        }
    }

    private static byte[] buildAad(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16 + PURPOSE.length);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        buffer.put(PURPOSE);
        return buffer.array();
    }

    private static void validateId(UUID id) {
        Objects.requireNonNull(id, "ID cannot be null");
    }

    private static void validateCode(String code) {
        if (!isValidCode(code)) {
            throw new IllegalArgumentException("Verification code must be exactly six decimal digits");
        }
    }

    private static boolean isValidCode(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < CODE_LENGTH; i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
