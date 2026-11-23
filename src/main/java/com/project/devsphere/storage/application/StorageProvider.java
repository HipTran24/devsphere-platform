package com.project.devsphere.storage.application;

public interface StorageProvider {

    String generatePresignedUploadUrl(String storageKey, String contentType, long expiresInSeconds);

    String generatePresignedDownloadUrl(String storageKey, long expiresInSeconds);
}
