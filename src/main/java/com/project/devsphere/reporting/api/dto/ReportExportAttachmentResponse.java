package com.project.devsphere.reporting.api.dto;

import java.util.UUID;

public record ReportExportAttachmentResponse(
        UUID attachmentId,
        UUID reportId,
        String format,
        String filename,
        String contentType,
        long fileSize,
        String sha256,
        String downloadUrl,
        long expiresInSeconds
) {}
