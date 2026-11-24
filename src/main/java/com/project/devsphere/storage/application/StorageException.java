package com.project.devsphere.storage.application;

import org.springframework.http.HttpStatus;

public class StorageException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public StorageException(HttpStatus status, String code, String message) {
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

    public static StorageException attachmentNotFound(String message) {
        return new StorageException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", message);
    }

    public static StorageException invalidFileSize(String message) {
        return new StorageException(HttpStatus.BAD_REQUEST, "INVALID_FILE_SIZE", message);
    }

    public static StorageException unsupportedMediaType(String message) {
        return new StorageException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", message);
    }

    public static StorageException invalidChecksum(String message) {
        return new StorageException(HttpStatus.BAD_REQUEST, "INVALID_CHECKSUM", message);
    }

    public static StorageException invalidParentTarget(String message) {
        return new StorageException(HttpStatus.BAD_REQUEST, "INVALID_PARENT_TARGET", message);
    }

    public static StorageException accessDenied(String message) {
        return new StorageException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }

    public static StorageException attachmentNotClean(String message) {
        return new StorageException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_CLEAN", message);
    }

    public static StorageException validationFailed(String message) {
        return new StorageException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public static StorageException storageObjectMissing(String message) {
        return new StorageException(HttpStatus.CONFLICT, "STORAGE_OBJECT_MISSING", message);
    }

    public static StorageException storageUnavailable(String message) {
        return new StorageException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", message);
    }
}
