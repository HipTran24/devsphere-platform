package com.project.devsphere.planning.application;

import org.springframework.http.HttpStatus;

public class PlanProposalException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public PlanProposalException(HttpStatus status, String code, String message) {
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

    public static PlanProposalException notFound(String message) {
        return new PlanProposalException(HttpStatus.NOT_FOUND, "PLAN_PROPOSAL_NOT_FOUND", message);
    }

    public static PlanProposalException planStale(String message) {
        return new PlanProposalException(HttpStatus.CONFLICT, "PLAN_STALE", message);
    }

    public static PlanProposalException versionConflict(String message) {
        return new PlanProposalException(HttpStatus.CONFLICT, "PROPOSAL_VERSION_CONFLICT", message);
    }

    public static PlanProposalException invalidPayload(String message) {
        return new PlanProposalException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PROPOSAL_PAYLOAD", message);
    }

    public static PlanProposalException accessDenied(String message) {
        return new PlanProposalException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static PlanProposalException validationFailed(String message) {
        return new PlanProposalException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
