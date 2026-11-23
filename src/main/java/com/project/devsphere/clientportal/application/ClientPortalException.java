package com.project.devsphere.clientportal.application;

import org.springframework.http.HttpStatus;

public class ClientPortalException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ClientPortalException(HttpStatus status, String code, String message) {
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

    public static ClientPortalException notFound(String message) {
        return new ClientPortalException(HttpStatus.NOT_FOUND, "MILESTONE_NOT_FOUND", message);
    }

    public static ClientPortalException accessDenied(String message) {
        return new ClientPortalException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static ClientPortalException notPublished(String message) {
        return new ClientPortalException(HttpStatus.FORBIDDEN, "MILESTONE_NOT_PUBLISHED", message);
    }

    public static ClientPortalException clientRoleRequired(String message) {
        return new ClientPortalException(HttpStatus.FORBIDDEN, "CLIENT_ROLE_REQUIRED", message);
    }

    public static ClientPortalException invalidDecision(String message) {
        return new ClientPortalException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_DECISION", message);
    }

    public static ClientPortalException unprocessable(String code, String message) {
        return new ClientPortalException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
