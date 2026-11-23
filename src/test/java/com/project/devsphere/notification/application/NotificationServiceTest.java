package com.project.devsphere.notification.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.notification.api.dto.NotificationResponse;
import com.project.devsphere.notification.domain.Notification;
import com.project.devsphere.notification.infrastructure.persistence.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private AccessPolicy accessPolicy;

    private NotificationService notificationService;
    private SessionService.Principal principal;
    private UUID userId;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationRepository, accessPolicy);
        userId = UUID.randomUUID();
        principal = new SessionService.Principal(userId, UUID.randomUUID(), "Alice", "USER");
    }

    @Test
    @DisplayName("createNotification: returns existing if already present (deduplication)")
    void createNotificationDeduplication() {
        String eventKey = "evt:123";
        Notification existing = new Notification();
        existing.setId(UUID.randomUUID());
        existing.setEventKey(eventKey);

        when(notificationRepository.findByUserIdAndEventKey(userId, eventKey)).thenReturn(Optional.of(existing));

        Notification result = notificationService.createNotification(
                UUID.randomUUID(), userId, UUID.randomUUID(), "TYPE", eventKey, "Title", "Body", "/link"
        );

        assertThat(result).isSameAs(existing);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("createNotification: saves new notification when not existing")
    void createNotificationSaveNew() {
        String eventKey = "evt:new";
        when(notificationRepository.findByUserIdAndEventKey(userId, eventKey)).thenReturn(Optional.empty());

        Notification saved = new Notification();
        saved.setId(UUID.randomUUID());
        saved.setEventKey(eventKey);
        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);

        Notification result = notificationService.createNotification(
                UUID.randomUUID(), userId, UUID.randomUUID(), "TYPE", eventKey, "Title", "Body", "/link"
        );

        assertThat(result).isNotNull();
        assertThat(result.getEventKey()).isEqualTo(eventKey);
    }

    @Test
    @DisplayName("createNotification: catches DataIntegrityViolationException on race and returns existing")
    void createNotificationRaceCondition() {
        String eventKey = "evt:race";
        when(notificationRepository.findByUserIdAndEventKey(userId, eventKey))
                .thenReturn(Optional.empty()) // first check
                .thenReturn(Optional.of(new Notification())); // fallback after catch

        when(notificationRepository.save(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint"));

        Notification result = notificationService.createNotification(
                UUID.randomUUID(), userId, UUID.randomUUID(), "TYPE", eventKey, "Title", "Body", "/link"
        );

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("listNotifications: filters unread vs all")
    void listNotificationsFiltering() {
        Notification n = new Notification();
        n.setId(UUID.randomUUID());
        n.setUserId(userId);
        n.setTitle("Notif");

        Page<Notification> page = new PageImpl<>(List.of(n));
        when(notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(eq(userId), any())).thenReturn(page);

        Page<NotificationResponse> result = notificationService.listNotifications(principal, true, PageRequest.of(0, 50));
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("markAsRead: rejects access to other user's notification")
    void markAsReadForbiddenDifferentUser() {
        UUID notifId = UUID.randomUUID();
        Notification n = new Notification();
        n.setId(notifId);
        n.setUserId(UUID.randomUUID()); // different user

        when(notificationRepository.findById(notifId)).thenReturn(Optional.of(n));

        assertThatThrownBy(() -> notificationService.markAsRead(principal, notifId))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    @DisplayName("markAsRead: updates readAt on unread notification")
    void markAsReadSuccess() {
        UUID notifId = UUID.randomUUID();
        Notification n = new Notification();
        n.setId(notifId);
        n.setUserId(userId);
        n.setReadAt(null);

        when(notificationRepository.findById(notifId)).thenReturn(Optional.of(n));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        Notification result = notificationService.markAsRead(principal, notifId);
        assertThat(result.getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("markAllAsRead: updates all unread notifications")
    void markAllAsReadSuccess() {
        when(notificationRepository.markAllAsRead(eq(userId), any(Instant.class))).thenReturn(5);

        int count = notificationService.markAllAsRead(principal);
        assertThat(count).isEqualTo(5);
    }
}
