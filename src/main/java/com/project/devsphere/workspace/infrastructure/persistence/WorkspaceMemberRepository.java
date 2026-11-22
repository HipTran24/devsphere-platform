package com.project.devsphere.workspace.infrastructure.persistence;

import com.project.devsphere.workspace.domain.WorkspaceMember;
import com.project.devsphere.sharedkernel.persistence.WorkspaceScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends WorkspaceScopedRepository<WorkspaceMember> {

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    Optional<WorkspaceMember> findByWorkspaceIdAndUserIdAndStatus(UUID workspaceId, UUID userId, WorkspaceMember.Status status);

    List<WorkspaceMember> findByWorkspaceIdAndStatus(UUID workspaceId, WorkspaceMember.Status status);

    List<WorkspaceMember> findByUserIdAndStatus(UUID userId, WorkspaceMember.Status status);

    boolean existsByWorkspaceIdAndUserIdAndStatus(UUID workspaceId, UUID userId, WorkspaceMember.Status status);

    boolean existsByWorkspaceIdAndUserIdAndRoleAndStatus(UUID workspaceId, UUID userId, WorkspaceMember.Role role, WorkspaceMember.Status status);
}
