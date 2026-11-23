package com.project.devsphere.storage.infrastructure;

import com.project.devsphere.storage.application.StorageException;

/**
 * Guards every object key and presign TTL before it reaches a storage backend.
 *
 * <p>Keys must be relative ({@code workspaces/...}), at most 1024 characters, free of {@code ..}
 * path segments, backslashes, NUL and other control characters. Presigned URLs live between 1 and
 * {@value #MAX_TTL_SECONDS} seconds.</p>
 */
public final class StorageKeyValidator {

    public static final int MAX_KEY_LENGTH = 1024;
    public static final long MIN_TTL_SECONDS = 1;
    public static final long MAX_TTL_SECONDS = 300;

    private StorageKeyValidator() {
    }

    public static String requireValidKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw StorageException.validationFailed("Storage key is required");
        }
        if (storageKey.length() > MAX_KEY_LENGTH) {
            throw StorageException.validationFailed("Storage key exceeds " + MAX_KEY_LENGTH + " characters");
        }
        if (storageKey.startsWith("/")) {
            throw StorageException.validationFailed("Storage key must be relative");
        }
        if (storageKey.indexOf('\\') >= 0) {
            throw StorageException.validationFailed("Storage key must not contain backslashes");
        }
        for (int i = 0; i < storageKey.length(); i++) {
            char c = storageKey.charAt(i);
            if (c == '\0' || Character.isISOControl(c) || c == '\u2028' || c == '\u2029') {
                throw StorageException.validationFailed("Storage key must not contain control characters");
            }
        }
        for (String segment : storageKey.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) {
                throw StorageException.validationFailed("Storage key must not contain relative path segments");
            }
        }
        if (storageKey.contains("..")) {
            throw StorageException.validationFailed("Storage key must not contain '..'");
        }
        return storageKey;
    }

    public static long requireValidTtl(long expiresInSeconds) {
        if (expiresInSeconds < MIN_TTL_SECONDS || expiresInSeconds > MAX_TTL_SECONDS) {
            throw StorageException.validationFailed(
                    "Presigned URL lifetime must be between " + MIN_TTL_SECONDS + " and " + MAX_TTL_SECONDS + " seconds");
        }
        return expiresInSeconds;
    }

    /** Accepts {@code type/subtype} media types without parameters, as locked into upload signatures. */
    public static String requireValidContentType(String contentType) {
        if (contentType == null || contentType.length() > 255
                || !contentType.matches("^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}$")) {
            throw StorageException.validationFailed("Content type must be a plain type/subtype media type");
        }
        return contentType;
    }
}
