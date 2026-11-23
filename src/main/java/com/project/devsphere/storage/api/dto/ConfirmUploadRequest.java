package com.project.devsphere.storage.api.dto;

import com.project.devsphere.storage.domain.Attachment;

public record ConfirmUploadRequest(
        Attachment.Visibility visibility
) {}
