package com.project.devsphere.communication.infrastructure.persistence;

import com.project.devsphere.communication.domain.ChatChannelMember;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatChannelMemberRepository extends ProjectScopedRepository<ChatChannelMember> {

    Optional<ChatChannelMember> findByChannelIdAndUserId(UUID channelId, UUID userId);

    boolean existsByChannelIdAndUserId(UUID channelId, UUID userId);

    List<ChatChannelMember> findByChannelIdOrderByJoinedAtAsc(UUID channelId);
}
