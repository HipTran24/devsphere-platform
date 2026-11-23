package com.project.devsphere.storage.api.dto;

import com.project.devsphere.storage.domain.Attachment;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        UUID workItemId,
        UUID chatMessageId,
        UUID reportId,
        UUID ciRunId,
        UUID uploadedBy,
        Attachment.Kind kind,
        String storageKey,
        String filename,
        String contentType,
        Long fileSize,
        String sha256,
        Attachment.Visibility visibility,
        Attachment.ScanStatus scanStatus,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static AttachmentResponse from(Attachment a) {
        return new AttachmentResponse(
                a.getId(),
                a.getWorkspaceId(),
                a.getProjectId(),
                a.getWorkItemId(),
                a.getChatMessageId(),
                a.getReportId(),
                a.getCiRunId(),
                a.getUploadedBy(),
                a.getKind(),
                a.getStorageKey(),
                a.getFilename(),
                a.getContentType(),
                a.getFileSize(),
                a.getSha256(),
                a.getVisibility(),
                a.getScanStatus(),
                a.getDeletedAt(),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getVersion()
        );
    }
}
