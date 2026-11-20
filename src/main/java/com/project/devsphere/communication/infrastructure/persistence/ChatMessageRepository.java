package com.project.devsphere.communication.infrastructure.persistence;

import com.project.devsphere.communication.domain.ChatMessage;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

public interface ChatMessageRepository extends ProjectScopedRepository<ChatMessage> {
}
