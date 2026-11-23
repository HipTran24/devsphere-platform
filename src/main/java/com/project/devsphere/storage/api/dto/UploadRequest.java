package com.project.devsphere.storage.api.dto;

import com.project.devsphere.storage.domain.Attachment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record UploadRequest(
        @NotBlank String filename,
        @NotBlank String contentType,
        @NotNull Long fileSize,
        @NotBlank String sha256,
        @NotNull Attachment.Kind kind,
        UUID workItemId,
        UUID chatMessageId,
        UUID reportId,
        UUID ciRunId,
        Attachment.Visibility visibility
) {}
