package com.project.devsphere.ai.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AI provider routing, HTTP timeouts, and secret encryption
 * ({@code devsphere.ai.*}).
 */
@ConfigurationProperties(prefix = "devsphere.ai")
public class AiProperties {

    /** 32-byte Base64 AES-256-GCM key for encrypting provider credentials; dev fallback derived when blank. */
    private String encryptionKey;

    /** Allows loopback and plain HTTP endpoints for {@code VLLM_SELF_HOST} providers (e.g., local dev/tests). */
    private boolean allowLoopbackSelfHost;

    private long connectTimeoutMs = 5000L;
    private long requestTimeoutMs = 60000L;
    private int maxResponseBytes = 2_097_152;

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public boolean isAllowLoopbackSelfHost() {
        return allowLoopbackSelfHost;
    }

    public void setAllowLoopbackSelfHost(boolean allowLoopbackSelfHost) {
        this.allowLoopbackSelfHost = allowLoopbackSelfHost;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public String toString() {
        // Never log or expose encryptionKey.
        return "AiProperties[allowLoopbackSelfHost=" + allowLoopbackSelfHost
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", requestTimeoutMs=" + requestTimeoutMs
                + ", maxResponseBytes=" + maxResponseBytes
                + ", encryptionKeyConfigured=" + (encryptionKey != null && !encryptionKey.isBlank()) + "]";
    }
}
