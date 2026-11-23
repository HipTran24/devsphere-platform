package com.project.devsphere.storage.api.dto;

import java.time.Instant;
import java.util.UUID;

public record DownloadResponse(
        UUID attachmentId,
        String filename,
        String contentType,
        String downloadUrl,
        Instant expiresAt
) {}
