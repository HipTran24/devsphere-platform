package com.project.devsphere.storage.infrastructure.persistence;

import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;
import com.project.devsphere.storage.domain.Attachment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository extends ProjectScopedRepository<Attachment> {

    Optional<Attachment> findByIdAndWorkspaceIdAndProjectId(UUID id, UUID workspaceId, UUID projectId);

    List<Attachment> findByProjectIdAndWorkItemIdAndDeletedAtIsNull(UUID projectId, UUID workItemId);

    List<Attachment> findByProjectIdAndChatMessageIdAndDeletedAtIsNull(UUID projectId, UUID chatMessageId);

    List<Attachment> findByProjectIdAndReportIdAndDeletedAtIsNull(UUID projectId, UUID reportId);

    List<Attachment> findByProjectIdAndCiRunIdAndDeletedAtIsNull(UUID projectId, UUID ciRunId);
}
