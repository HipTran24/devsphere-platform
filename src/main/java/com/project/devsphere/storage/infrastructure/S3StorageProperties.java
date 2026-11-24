package com.project.devsphere.storage.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Object storage settings ({@code devsphere.storage.s3.*}).
 *
 * <p>{@code mode=local} (default) issues HMAC-signed development URLs; {@code mode=aws} issues real
 * AWS Signature V4 presigned URLs through the AWS SDK. Credentials are never configured here: the
 * AWS provider uses the SDK default credentials chain (environment, web identity, instance role).</p>
 */
@ConfigurationProperties(prefix = "devsphere.storage.s3")
public class S3StorageProperties {

    /** {@code local} or {@code aws}. */
    private String mode = "local";
    /** Public base URL for local mode, or an optional S3-compatible endpoint override in aws mode. */
    private String endpoint;
    private String bucket = "devsphere-attachments";
    private String region = "us-east-1";
    /** HMAC key for local-mode URL signatures; a random per-process key is used when blank. */
    private String signingSecret;
    /** Use path-style addressing (required by most S3-compatible endpoints). */
    private boolean pathStyleAccess;
    /** Test-only escape hatch allowing http and loopback endpoints; metadata addresses stay blocked. */
    private boolean allowInsecureEndpoint;
    /** Whether confirmUpload verifies object presence via HTTP HEAD in aws mode. */
    private boolean verifyOnConfirm = false;
    /** HTTP connect/request timeout in milliseconds for presigned HEAD/PUT calls. */
    private long httpTimeoutMs = 10000L;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public boolean isAllowInsecureEndpoint() {
        return allowInsecureEndpoint;
    }

    public void setAllowInsecureEndpoint(boolean allowInsecureEndpoint) {
        this.allowInsecureEndpoint = allowInsecureEndpoint;
    }

    public boolean isVerifyOnConfirm() {
        return verifyOnConfirm;
    }

    public void setVerifyOnConfirm(boolean verifyOnConfirm) {
        this.verifyOnConfirm = verifyOnConfirm;
    }

    public long getHttpTimeoutMs() {
        return httpTimeoutMs;
    }

    public void setHttpTimeoutMs(long httpTimeoutMs) {
        this.httpTimeoutMs = httpTimeoutMs;
    }

    @Override
    public String toString() {
        // Never render the signing secret.
        return "S3StorageProperties[mode=" + mode + ", bucket=" + bucket + ", region=" + region
                + ", endpoint=" + endpoint + ", pathStyleAccess=" + pathStyleAccess
                + ", verifyOnConfirm=" + verifyOnConfirm + ", httpTimeoutMs=" + httpTimeoutMs + "]";
    }
}
