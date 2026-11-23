package com.project.devsphere.workspace.infrastructure.persistence;

import com.project.devsphere.workspace.domain.Workspace;
import com.project.devsphere.sharedkernel.persistence.EntityRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends EntityRepository<Workspace> {

    boolean existsBySlug(String slug);

    Optional<Workspace> findBySlug(String slug);

    @Query("SELECT w FROM Workspace w WHERE w.id IN (" +
            "  SELECT wm.workspaceId FROM WorkspaceMember wm " +
            "  WHERE wm.userId = :userId AND wm.status = com.project.devsphere.workspace.domain.WorkspaceMember.Status.ACTIVE" +
            ")")
    Page<Workspace> findActiveWorkspacesByUserId(@Param("userId") UUID userId, Pageable pageable);
}
