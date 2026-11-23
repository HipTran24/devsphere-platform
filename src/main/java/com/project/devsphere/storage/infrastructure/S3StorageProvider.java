package com.project.devsphere.storage.infrastructure;

import com.project.devsphere.storage.application.StorageProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class S3StorageProvider implements StorageProvider {

    private final String endpoint;
    private final String bucket;

    public S3StorageProvider(
            @Value("${devsphere.storage.s3.endpoint:https://storage.devsphere.local}") String endpoint,
            @Value("${devsphere.storage.s3.bucket:devsphere-attachments}") String bucket) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.bucket = bucket;
    }

    @Override
    public String generatePresignedUploadUrl(String storageKey, String contentType, long expiresInSeconds) {
        long expiryEpoch = Instant.now().plusSeconds(expiresInSeconds).getEpochSecond();
        String signature = computeSignature(storageKey, "PUT", expiryEpoch);
        return String.format("%s/%s/%s?action=upload&contentType=%s&expires=%d&sig=%s",
                endpoint,
                bucket,
                encodeKey(storageKey),
                URLEncoder.encode(contentType, StandardCharsets.UTF_8),
                expiryEpoch,
                signature);
    }

    @Override
    public String generatePresignedDownloadUrl(String storageKey, long expiresInSeconds) {
        long expiryEpoch = Instant.now().plusSeconds(expiresInSeconds).getEpochSecond();
        String signature = computeSignature(storageKey, "GET", expiryEpoch);
        return String.format("%s/%s/%s?action=download&expires=%d&sig=%s",
                endpoint,
                bucket,
                encodeKey(storageKey),
                expiryEpoch,
                signature);
    }

    private String encodeKey(String storageKey) {
        return URLEncoder.encode(storageKey, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String computeSignature(String storageKey, String method, long expiryEpoch) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String raw = method + ":" + storageKey + ":" + expiryEpoch;
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "simulated-sig-" + expiryEpoch;
        }
    }
}
