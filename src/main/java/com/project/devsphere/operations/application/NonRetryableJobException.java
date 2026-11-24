package com.project.devsphere.operations.application;

/** Failure that retrying cannot fix (malformed payload, missing aggregate); the job is dead-lettered. */
public class NonRetryableJobException extends RuntimeException {

    public NonRetryableJobException(String message) {
        super(message);
    }

    public NonRetryableJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
