package com.project.devsphere.storage.application;

/**
 * Outbound client for verifying uploaded objects and uploading server-generated exports using
 * presigned storage URLs.
 */
public interface StorageObjectClient {

    /**
     * Issues a {@code HEAD} request against {@code presignedUrl}.
     *
     * @return {@code true} on 2xx, {@code false} on 404
     * @throws StorageException (503 {@code STORAGE_UNAVAILABLE}) on transport/5xx/unexpected error,
     *                          or 400 {@code VALIDATION_FAILED} when URL validation fails
     */
    boolean verifyObjectExists(String presignedUrl);

    /**
     * Issues a {@code PUT} request uploading {@code content} to {@code presignedPutUrl}.
     *
     * @throws StorageException (503 {@code STORAGE_UNAVAILABLE}) on transport/non-2xx error,
     *                          or 400 {@code VALIDATION_FAILED} when input/URL validation fails
     */
    void uploadObject(String presignedPutUrl, byte[] content, String contentType);
}
