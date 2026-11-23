package com.project.devsphere.monitoring.application;

import org.springframework.http.HttpStatus;

public class AlertException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AlertException(HttpStatus status, String code, String message) {
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

    public static AlertException notFound(String message) {
        return new AlertException(HttpStatus.NOT_FOUND, "ALERT_NOT_FOUND", message);
    }

    public static AlertException conflict(String code, String message) {
        return new AlertException(HttpStatus.CONFLICT, code, message);
    }

    public static AlertException versionConflict(String message) {
        return new AlertException(HttpStatus.CONFLICT, "ALERT_VERSION_CONFLICT", message);
    }

    public static AlertException invalidTransition(String message) {
        return new AlertException(HttpStatus.CONFLICT, "INVALID_ALERT_TRANSITION", message);
    }

    public static AlertException unprocessable(String code, String message) {
        return new AlertException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static AlertException accessDenied(String message) {
        return new AlertException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static AlertException validationFailed(String message) {
        return new AlertException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
