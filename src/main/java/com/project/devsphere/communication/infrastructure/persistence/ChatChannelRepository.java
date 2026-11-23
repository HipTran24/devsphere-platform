package com.project.devsphere.communication.infrastructure.persistence;

import com.project.devsphere.communication.domain.ChatChannel;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatChannelRepository extends ProjectScopedRepository<ChatChannel> {

    Optional<ChatChannel> findByIdAndWorkspaceIdAndProjectId(UUID id, UUID workspaceId, UUID projectId);

    @Query("""
        SELECT c FROM ChatChannel c
        WHERE c.workspaceId = :workspaceId
          AND c.projectId = :projectId
          AND EXISTS (
              SELECT 1 FROM ChatChannelMember m
              WHERE m.channelId = c.id AND m.userId = :userId
          )
        ORDER BY c.createdAt DESC
    """)
    List<ChatChannel> findUserChannels(
            @Param("workspaceId") UUID workspaceId,
            @Param("projectId") UUID projectId,
            @Param("userId") UUID userId
    );

    List<ChatChannel> findByWorkspaceIdAndProjectIdOrderByCreatedAtDesc(UUID workspaceId, UUID projectId);
}
