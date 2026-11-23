package com.project.devsphere.storage.api.dto;

import java.util.UUID;

public record DownloadAttachmentResponse(
        UUID attachmentId,
        String downloadUrl,
        long expiresInSeconds
) {}
