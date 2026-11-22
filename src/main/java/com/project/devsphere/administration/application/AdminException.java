package com.project.devsphere.administration.application;

import org.springframework.http.HttpStatus;

public class AdminException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AdminException(HttpStatus status, String code, String message) {
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

    public static AdminException notFound(String message) {
        return new AdminException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }

    public static AdminException concurrentChange(String message) {
        return new AdminException(HttpStatus.CONFLICT, "CONCURRENT_ACCOUNT_CHANGE", message);
    }

    public static AdminException stateTransitionInvalid(String message) {
        return new AdminException(HttpStatus.CONFLICT, "STATE_TRANSITION_INVALID", message);
    }

    public static AdminException lastActiveAdmin(String message) {
        return new AdminException(HttpStatus.CONFLICT, "LAST_ACTIVE_ADMIN", message);
    }

    public static AdminException validationFailed(String message) {
        return new AdminException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static AdminException forbidden(String message) {
        return new AdminException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static AdminException unauthorized(String message) {
        return new AdminException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }
}
