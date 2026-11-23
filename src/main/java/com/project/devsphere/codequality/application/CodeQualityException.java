package com.project.devsphere.codequality.application;

import org.springframework.http.HttpStatus;

public class CodeQualityException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CodeQualityException(HttpStatus status, String code, String message) {
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

    public static CodeQualityException authorIndependenceViolation(String message) {
        return new CodeQualityException(HttpStatus.UNPROCESSABLE_ENTITY, "REVIEW_AUTHOR_INDEPENDENCE_VIOLATION", message);
    }

    public static CodeQualityException prHeadShaMismatch(String message) {
        return new CodeQualityException(HttpStatus.CONFLICT, "PR_HEAD_SHA_MISMATCH", message);
    }

    public static CodeQualityException qualityGateEvidenceMissing(String message) {
        return new CodeQualityException(HttpStatus.UNPROCESSABLE_ENTITY, "QUALITY_GATE_EVIDENCE_MISSING", message);
    }

    public static CodeQualityException ciRunNotFound(String message) {
        return new CodeQualityException(HttpStatus.NOT_FOUND, "CI_RUN_NOT_FOUND", message);
    }

    public static CodeQualityException codeReviewNotFound(String message) {
        return new CodeQualityException(HttpStatus.NOT_FOUND, "CODE_REVIEW_NOT_FOUND", message);
    }

    public static CodeQualityException accessDenied(String message) {
        return new CodeQualityException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static CodeQualityException validationFailed(String message) {
        return new CodeQualityException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static CodeQualityException invalidHeadSha(String message) {
        return new CodeQualityException(HttpStatus.BAD_REQUEST, "INVALID_HEAD_SHA", message);
    }
}
