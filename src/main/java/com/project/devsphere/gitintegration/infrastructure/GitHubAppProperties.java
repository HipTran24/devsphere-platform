package com.project.devsphere.gitintegration.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for GitHub App authentication and REST API sync ({@code devsphere.github.app.*}).
 */
@ConfigurationProperties(prefix = "devsphere.github.app")
public class GitHubAppProperties {

    private boolean enabled = false;
    private String appId;
    private String privateKeyPem;
    private String apiBaseUrl = "https://api.github.com";
    private boolean allowLoopbackApi = false;
    private long connectTimeoutMs = 5000L;
    private long requestTimeoutMs = 30000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public boolean isAllowLoopbackApi() {
        return allowLoopbackApi;
    }

    public void setAllowLoopbackApi(boolean allowLoopbackApi) {
        this.allowLoopbackApi = allowLoopbackApi;
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

    @Override
    public String toString() {
        // Never log or expose privateKeyPem.
        return "GitHubAppProperties[enabled=" + enabled
                + ", appIdConfigured=" + (appId != null && !appId.isBlank())
                + ", privateKeyConfigured=" + (privateKeyPem != null && !privateKeyPem.isBlank())
                + ", apiBaseUrl=" + apiBaseUrl
                + ", allowLoopbackApi=" + allowLoopbackApi
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", requestTimeoutMs=" + requestTimeoutMs + "]";
    }
}
