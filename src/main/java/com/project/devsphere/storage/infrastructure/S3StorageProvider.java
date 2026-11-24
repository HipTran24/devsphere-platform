package com.project.devsphere.storage.infrastructure;

import com.project.devsphere.storage.application.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Development storage adapter issuing HMAC-SHA256 signed URLs in the form
 * {@code <endpoint>/<bucket>/<key>?action=upload&contentType=..&expires=..&sig=..}.
 * Active unless {@code devsphere.storage.s3.mode=aws}.
 */
@Component
@ConditionalOnProperty(name = "devsphere.storage.s3.mode", havingValue = "local", matchIfMissing = true)
public class S3StorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(S3StorageProvider.class);
    private static final String DEFAULT_ENDPOINT = "https://storage.devsphere.local";

    private final String endpoint;
    private final String bucket;
    private final byte[] signingKey;
    private final Clock clock;

    public S3StorageProvider(String endpoint, String bucket) {
        this(endpoint, bucket, null, Clock.systemUTC());
    }

    @Autowired
    public S3StorageProvider(S3StorageProperties properties) {
        this(properties.getEndpoint(), properties.getBucket(), properties.getSigningSecret(), Clock.systemUTC());
    }

    S3StorageProvider(String endpoint, String bucket, String signingSecret, Clock clock) {
        String base = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.strip();
        this.endpoint = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.bucket = bucket == null || bucket.isBlank() ? "devsphere-attachments" : bucket.strip();
        this.clock = clock;
        if (signingSecret == null || signingSecret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.signingKey = random;
            log.warn("devsphere.storage.s3.signing-secret is not set; local storage URLs use an ephemeral per-process key");
        } else {
            this.signingKey = signingSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public String generatePresignedUploadUrl(String storageKey, String contentType, long expiresInSeconds) {
        StorageKeyValidator.requireValidKey(storageKey);
        StorageKeyValidator.requireValidTtl(expiresInSeconds);
        String type = StorageKeyValidator.requireValidContentType(contentType).toLowerCase(Locale.ROOT);
        long expiryEpoch = Instant.now(clock).plusSeconds(expiresInSeconds).getEpochSecond();
        String signature = sign("PUT", storageKey, expiryEpoch, type);
        return String.format("%s/%s/%s?action=upload&contentType=%s&expires=%d&sig=%s",
                endpoint,
                bucket,
                encodeKey(storageKey),
                URLEncoder.encode(type, StandardCharsets.UTF_8),
                expiryEpoch,
                signature);
    }

    @Override
    public String generatePresignedDownloadUrl(String storageKey, long expiresInSeconds) {
        StorageKeyValidator.requireValidKey(storageKey);
        StorageKeyValidator.requireValidTtl(expiresInSeconds);
        long expiryEpoch = Instant.now(clock).plusSeconds(expiresInSeconds).getEpochSecond();
        String signature = sign("GET", storageKey, expiryEpoch, "");
        return String.format("%s/%s/%s?action=download&expires=%d&sig=%s",
                endpoint,
                bucket,
                encodeKey(storageKey),
                expiryEpoch,
                signature);
    }

    @Override
    public String generatePresignedHeadUrl(String storageKey, long expiresInSeconds) {
        StorageKeyValidator.requireValidKey(storageKey);
        StorageKeyValidator.requireValidTtl(expiresInSeconds);
        long expiryEpoch = Instant.now(clock).plusSeconds(expiresInSeconds).getEpochSecond();
        String signature = sign("HEAD", storageKey, expiryEpoch, "");
        return String.format("%s/%s/%s?action=head&expires=%d&sig=%s",
                endpoint,
                bucket,
                encodeKey(storageKey),
                expiryEpoch,
                signature);
    }

    @Override
    public String mode() {
        return "local";
    }

    @Override
    public String bucket() {
        return bucket;
    }

    /** Constant-time verification of a URL signature produced by this provider. */
    public boolean verifySignature(String method, String storageKey, long expiryEpoch, String contentType, String signature) {
        if (signature == null || Instant.now(clock).getEpochSecond() > expiryEpoch) {
            return false;
        }
        String expected = sign(method, storageKey, expiryEpoch, contentType == null ? "" : contentType.toLowerCase(Locale.ROOT));
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
    }

    private String encodeKey(String storageKey) {
        return URLEncoder.encode(storageKey, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sign(String method, String storageKey, long expiryEpoch, String contentType) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            String canonical = method + "\n" + bucket + "\n" + storageKey + "\n" + expiryEpoch + "\n" + contentType;
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable", ex);
        }
    }
}
