package com.project.devsphere.workspace.infrastructure.persistence;

import com.project.devsphere.workspace.domain.WorkspaceInvitation;
import com.project.devsphere.sharedkernel.persistence.WorkspaceScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceInvitationRepository extends WorkspaceScopedRepository<WorkspaceInvitation> {

    Optional<WorkspaceInvitation> findByTokenHash(String tokenHash);

    Optional<WorkspaceInvitation> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    List<WorkspaceInvitation> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    Page<WorkspaceInvitation> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    boolean existsByWorkspaceIdAndEmailAndStatus(UUID workspaceId, String email, WorkspaceInvitation.Status status);
}
