package com.project.devsphere.notification.infrastructure.persistence;

import com.project.devsphere.notification.domain.Notification;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends ProjectScopedRepository<Notification> {

    Optional<Notification> findById(UUID id);

    Optional<Notification> findByUserIdAndEventKey(UUID userId, String eventKey);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID userId);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllAsRead(@Param("userId") UUID userId, @Param("now") Instant now);
}
