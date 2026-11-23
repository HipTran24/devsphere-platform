package com.project.devsphere.notification.application;

import org.springframework.http.HttpStatus;

public class NotificationException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public NotificationException(HttpStatus status, String code, String message) {
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

    public static NotificationException notificationNotFound(String message) {
        return new NotificationException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", message);
    }

    public static NotificationException accessDenied(String message) {
        return new NotificationException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static NotificationException validationFailed(String message) {
        return new NotificationException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
