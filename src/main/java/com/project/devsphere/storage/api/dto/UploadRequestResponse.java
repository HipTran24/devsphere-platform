package com.project.devsphere.storage.api.dto;

import com.project.devsphere.storage.domain.Attachment;

import java.util.UUID;

public record UploadRequestResponse(
        UUID attachmentId,
        String storageKey,
        String uploadUrl,
        long expiresInSeconds,
        Attachment.ScanStatus scanStatus,
        Attachment.Visibility visibility
) {}
