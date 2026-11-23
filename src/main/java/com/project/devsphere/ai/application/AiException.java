package com.project.devsphere.ai.application;

import org.springframework.http.HttpStatus;

public class AiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AiException(HttpStatus status, String code, String message) {
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

    public static AiException notFound(String message) {
        return new AiException(HttpStatus.NOT_FOUND, "AI_JOB_NOT_FOUND", message);
    }

    public static AiException accessDenied(String message) {
        return new AiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static AiException invalidInput(String message) {
        return new AiException(HttpStatus.BAD_REQUEST, "INVALID_AI_JOB_INPUT", message);
    }

    public static AiException validationFailed(String message) {
        return new AiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_VALIDATION_FAILED", message);
    }

    public static AiException maxAttemptsExceeded(String message) {
        return new AiException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_MAX_VALIDATION_ATTEMPTS_EXCEEDED", message);
    }

    public static AiException unprocessable(String code, String message) {
        return new AiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
