package com.project.devsphere.reporting.application;

import org.springframework.http.HttpStatus;

public class ReportException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ReportException(HttpStatus status, String code, String message) {
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

    public static ReportException notFound(String message) {
        return new ReportException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", message);
    }

    public static ReportException accessDenied(String message) {
        return new ReportException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static ReportException invalidPeriod(String message) {
        return new ReportException(HttpStatus.BAD_REQUEST, "INVALID_REPORT_PERIOD", message);
    }

    public static ReportException invalidInput(String message) {
        return new ReportException(HttpStatus.BAD_REQUEST, "INVALID_REPORT_INPUT", message);
    }

    public static ReportException aiJobNotValid(String message) {
        return new ReportException(HttpStatus.UNPROCESSABLE_ENTITY, "AI_JOB_NOT_VALID", message);
    }

    public static ReportException unprocessable(String code, String message) {
        return new ReportException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
