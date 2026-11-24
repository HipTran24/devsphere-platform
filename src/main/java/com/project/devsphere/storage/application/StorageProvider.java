package com.project.devsphere.storage.application;

public interface StorageProvider {

    String generatePresignedUploadUrl(String storageKey, String contentType, long expiresInSeconds);

    String generatePresignedDownloadUrl(String storageKey, long expiresInSeconds);

    /** Short adapter identifier (for example {@code local} or {@code aws}) used in health reporting. */
    default String mode() {
        return getClass().getSimpleName();
    }

    /** Target bucket, when the adapter has one. */
    default String bucket() {
        return null;
    }
}
