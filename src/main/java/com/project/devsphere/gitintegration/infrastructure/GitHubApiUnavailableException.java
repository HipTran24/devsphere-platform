package com.project.devsphere.gitintegration.infrastructure;

/**
 * Non-fatal runtime exception indicating the GitHub REST API or GitHub App authentication is
 * currently unavailable, rate-limited, unconfigured, or unreachable.
 * Mapped to HTTP 503 {@code GITHUB_API_UNAVAILABLE}.
 */
public class GitHubApiUnavailableException extends RuntimeException {

    public static final String ERROR_CODE = "GITHUB_API_UNAVAILABLE";

    private final Long retryAfterSeconds;

    public GitHubApiUnavailableException(String message) {
        this(message, null, null);
    }

    public GitHubApiUnavailableException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public GitHubApiUnavailableException(String message, Long retryAfterSeconds) {
        this(message, null, retryAfterSeconds);
    }

    public GitHubApiUnavailableException(String message, Throwable cause, Long retryAfterSeconds) {
        super(message, cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getCode() {
        return ERROR_CODE;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
