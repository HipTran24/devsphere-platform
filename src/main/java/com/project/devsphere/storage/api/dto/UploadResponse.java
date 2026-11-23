package com.project.devsphere.storage.api.dto;

import java.time.Instant;
import java.util.UUID;

public record UploadResponse(
        UUID attachmentId,
        String storageKey,
        String uploadUrl,
        Instant expiresAt
) {}
