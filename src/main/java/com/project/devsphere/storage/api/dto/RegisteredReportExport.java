package com.project.devsphere.storage.api.dto;

import com.project.devsphere.storage.domain.Attachment;

public record RegisteredReportExport(
        Attachment attachment,
        String downloadUrl,
        long expiresInSeconds
) {}
