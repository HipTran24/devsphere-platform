package com.project.devsphere.gitintegration.application;

import org.springframework.http.HttpStatus;

public class GitHubException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public GitHubException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static GitHubException webhookSignatureInvalid(String message) {
        return new GitHubException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID", message);
    }

    public static GitHubException duplicateRepository(String message) {
        return new GitHubException(HttpStatus.CONFLICT, "DUPLICATE_REPOSITORY", message);
    }

    public static GitHubException repositoryAlreadyConnected(String message) {
        return new GitHubException(HttpStatus.CONFLICT, "REPOSITORY_ALREADY_CONNECTED", message);
    }

    public static GitHubException repositoryNotFound(String message) {
        return new GitHubException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", message);
    }

    public static GitHubException duplicateDelivery(String message) {
        return new GitHubException(HttpStatus.OK, "WEBHOOK_DELIVERY_DUPLICATE", message);
    }

    public static GitHubException duplicateResource(String message) {
        return new GitHubException(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", message);
    }

    public static GitHubException accessDenied(String message) {
        return new GitHubException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static GitHubException validationFailed(String message) {
        return new GitHubException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
