package com.project.devsphere.resource.application;

import org.springframework.http.HttpStatus;

public class ResourceCapacityException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ResourceCapacityException(HttpStatus status, String code, String message) {
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

    public static ResourceCapacityException capacityMissing(String message) {
        return new ResourceCapacityException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_CAPACITY_MISSING", message);
    }

    public static ResourceCapacityException resourceCapacityMissing(String message) {
        return capacityMissing(message);
    }

    public static ResourceCapacityException notFound(String message) {
        return new ResourceCapacityException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }

    public static ResourceCapacityException resourceNotFound(String message) {
        return notFound(message);
    }

    public static ResourceCapacityException periodOverlap(String message) {
        return new ResourceCapacityException(HttpStatus.CONFLICT, "RESOURCE_PERIOD_OVERLAP", message);
    }

    public static ResourceCapacityException resourcePeriodOverlap(String message) {
        return periodOverlap(message);
    }

    public static ResourceCapacityException timeOffOverlap(String message) {
        return new ResourceCapacityException(HttpStatus.CONFLICT, "TIME_OFF_OVERLAP", message);
    }

    public static ResourceCapacityException calendarInvalid(String message) {
        return new ResourceCapacityException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_CALENDAR_INVALID", message);
    }

    public static ResourceCapacityException resourceCalendarInvalid(String message) {
        return calendarInvalid(message);
    }

    public static ResourceCapacityException developerNotEligible(String message) {
        return new ResourceCapacityException(HttpStatus.UNPROCESSABLE_ENTITY, "DEVELOPER_NOT_ELIGIBLE", message);
    }

    public static ResourceCapacityException planStale(String message) {
        return new ResourceCapacityException(HttpStatus.CONFLICT, "PLAN_STALE", message);
    }

    public static ResourceCapacityException accessDenied(String message) {
        return new ResourceCapacityException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static ResourceCapacityException assigneeNotDeveloper(String message) {
        return new ResourceCapacityException(HttpStatus.BAD_REQUEST, "ASSIGNEE_NOT_DEVELOPER", message);
    }

    public static ResourceCapacityException validationFailed(String message) {
        return new ResourceCapacityException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }
}
