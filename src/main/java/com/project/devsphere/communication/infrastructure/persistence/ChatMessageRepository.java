package com.project.devsphere.communication.infrastructure.persistence;

import com.project.devsphere.communication.domain.ChatMessage;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMessageRepository extends ProjectScopedRepository<ChatMessage> {

    List<ChatMessage> findByChannelIdOrderBySentAtAsc(UUID channelId);

    Page<ChatMessage> findByChannelIdOrderBySentAtAsc(UUID channelId, Pageable pageable);

    Optional<ChatMessage> findByIdAndChannelId(UUID id, UUID channelId);

    Optional<ChatMessage> findById(UUID id);
}
