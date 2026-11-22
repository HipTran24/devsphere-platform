package com.project.devsphere.workspace.application;

import org.springframework.http.HttpStatus;

public class WorkspaceProjectException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public WorkspaceProjectException(HttpStatus status, String code, String message) {
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

    public static WorkspaceProjectException duplicate(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", message);
    }

    public static WorkspaceProjectException duplicateResource(String message) {
        return duplicate(message);
    }

    public static WorkspaceProjectException planStale(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "PLAN_STALE", message);
    }

    public static WorkspaceProjectException notFound(String message) {
        return new WorkspaceProjectException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }

    public static WorkspaceProjectException resourceNotFound(String message) {
        return notFound(message);
    }

    public static WorkspaceProjectException activationPreconditionFailed(String message) {
        return new WorkspaceProjectException(HttpStatus.UNPROCESSABLE_ENTITY, "ACTIVATION_PRECONDITION_FAILED", message);
    }

    public static WorkspaceProjectException clientDeadlineConfirmationRequired(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "CLIENT_DEADLINE_CONFIRMATION_REQUIRED", message);
    }

    public static WorkspaceProjectException accessDenied(String message) {
        return new WorkspaceProjectException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static WorkspaceProjectException validationFailed(String message) {
        return new WorkspaceProjectException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static WorkspaceProjectException badRequest(String message) {
        return validationFailed(message);
    }

    public static WorkspaceProjectException lastOwner(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "LAST_OWNER", message);
    }

    public static WorkspaceProjectException activeProjectRequiresPm(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "ACTIVE_PROJECT_REQUIRES_PM", message);
    }

    public static WorkspaceProjectException invitationExpired(String message) {
        return new WorkspaceProjectException(HttpStatus.GONE, "INVITATION_EXPIRED", message);
    }

    public static WorkspaceProjectException pmTransferNotAccepted(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "PM_TRANSFER_NOT_ACCEPTED", message);
    }

    public static WorkspaceProjectException pmRoleRestricted(String message) {
        return new WorkspaceProjectException(HttpStatus.BAD_REQUEST, "PM_ROLE_RESTRICTED", message);
    }

    public static WorkspaceProjectException dependencyCycle(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "DEPENDENCY_CYCLE", message);
    }

    public static WorkspaceProjectException scopeLocked(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "SCOPE_LOCKED", message);
    }

    public static WorkspaceProjectException invalidHierarchy(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "INVALID_HIERARCHY", message);
    }

    public static WorkspaceProjectException hierarchyCycle(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "HIERARCHY_CYCLE", message);
    }

    public static WorkspaceProjectException assigneeNotDeveloper(String message) {
        return new WorkspaceProjectException(HttpStatus.BAD_REQUEST, "ASSIGNEE_NOT_DEVELOPER", message);
    }

    public static WorkspaceProjectException invalidStateTransition(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", message);
    }

    public static WorkspaceProjectException taskAcceptanceRequiresPm(String message) {
        return new WorkspaceProjectException(HttpStatus.FORBIDDEN, "TASK_ACCEPTANCE_REQUIRES_PM", message);
    }

    public static WorkspaceProjectException activeSprintExists(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "ACTIVE_SPRINT_EXISTS", message);
    }

    public static WorkspaceProjectException sprintClosed(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "SPRINT_CLOSED", message);
    }

    public static WorkspaceProjectException invalidSprintDates(String message) {
        return new WorkspaceProjectException(HttpStatus.BAD_REQUEST, "INVALID_SPRINT_DATES", message);
    }

    public static WorkspaceProjectException sprintNotPlanned(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "SPRINT_NOT_PLANNED", message);
    }

    public static WorkspaceProjectException sprintNotActive(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "SPRINT_NOT_ACTIVE", message);
    }

    public static WorkspaceProjectException cannotModifyDoneItemSprint(String message) {
        return new WorkspaceProjectException(HttpStatus.CONFLICT, "CANNOT_MODIFY_DONE_ITEM_SPRINT", message);
    }

    public static WorkspaceProjectException qualityGateEvidenceMissing(String message) {
        return new WorkspaceProjectException(HttpStatus.UNPROCESSABLE_ENTITY, "QUALITY_GATE_EVIDENCE_MISSING", message);
    }
}
