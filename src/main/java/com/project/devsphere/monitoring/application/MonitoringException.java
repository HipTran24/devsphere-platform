package com.project.devsphere.monitoring.application;

import org.springframework.http.HttpStatus;

public class MonitoringException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public MonitoringException(HttpStatus status, String code, String message) {
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

    public static MonitoringException dependencyCycleDetected(String message) {
        return new MonitoringException(HttpStatus.UNPROCESSABLE_ENTITY, "DEPENDENCY_CYCLE_DETECTED", message);
    }

    public static MonitoringException resourceNotFound(String message) {
        return new MonitoringException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }

    public static MonitoringException validationFailed(String message) {
        return new MonitoringException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static MonitoringException accessDenied(String message) {
        return new MonitoringException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static MonitoringException conflict(String message) {
        return new MonitoringException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public static MonitoringException evidenceInsufficient(String message) {
        return new MonitoringException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_EVIDENCE", message);
    }
}
