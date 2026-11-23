package com.project.devsphere.operations.application;

import org.springframework.http.HttpStatus;

/** Operations-domain failure carrying an HTTP status and a stable machine-readable code. */
public class OperationsException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public OperationsException(HttpStatus status, String code, String message) {
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

    public static OperationsException validationFailed(String message) {
        return new OperationsException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static OperationsException notFound(String message) {
        return new OperationsException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }

    public static OperationsException idempotencyConflict(String message) {
        return new OperationsException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", message);
    }

    public static OperationsException stateTransitionInvalid(String message) {
        return new OperationsException(HttpStatus.CONFLICT, "STATE_TRANSITION_INVALID", message);
    }

    public static OperationsException schedulerUnavailable(String message) {
        return new OperationsException(HttpStatus.SERVICE_UNAVAILABLE, "SCHEDULER_UNAVAILABLE", message);
    }
}
