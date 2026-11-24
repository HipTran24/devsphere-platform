package com.project.devsphere.notification.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.notification.api.dto.NotificationResponse;
import com.project.devsphere.notification.domain.Notification;
import com.project.devsphere.notification.infrastructure.persistence.NotificationRepository;
import com.project.devsphere.operations.application.OutboxService;
import com.project.devsphere.operations.domain.BackgroundJob;
import com.project.devsphere.realtime.application.RealtimeEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final AccessPolicy accessPolicy;
    private final ObjectProvider<OutboxService> outboxService;
    private final ObjectProvider<RealtimeEventPublisher> realtimeEventPublisher;

    public NotificationService(
            NotificationRepository notificationRepository,
            AccessPolicy accessPolicy) {
        this(notificationRepository, accessPolicy, null, null);
    }

    /**
     * @param outboxService optional outbox used to queue e-mail delivery for notifications created
     *                      with {@link Notification.EmailStatus#PENDING}; may be {@code null}.
     */
    public NotificationService(
            NotificationRepository notificationRepository,
            AccessPolicy accessPolicy,
            ObjectProvider<OutboxService> outboxService) {
        this(notificationRepository, accessPolicy, outboxService, null);
    }

    @Autowired
    public NotificationService(
            NotificationRepository notificationRepository,
            AccessPolicy accessPolicy,
            ObjectProvider<OutboxService> outboxService,
            ObjectProvider<RealtimeEventPublisher> realtimeEventPublisher) {
        this.notificationRepository = Objects.requireNonNull(notificationRepository, "notificationRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.outboxService = outboxService;
        this.realtimeEventPublisher = realtimeEventPublisher;
    }

    public Notification createNotification(
            UUID workspaceId,
            UUID userId,
            UUID projectId,
            String eventType,
            String eventKey,
            String title,
            String body,
            String deeplink) {
        return createNotification(workspaceId, userId, projectId, eventType, eventKey, title, body, deeplink, Notification.EmailStatus.NOT_REQUESTED);
    }

    public Notification createNotification(
            UUID workspaceId,
            UUID userId,
            UUID projectId,
            String eventType,
            String eventKey,
            String title,
            String body,
            String deeplink,
            Notification.EmailStatus emailStatus) {

        if (userId == null) {
            throw NotificationException.validationFailed("userId must not be null");
        }
        if (eventType == null || eventType.isBlank()) {
            throw NotificationException.validationFailed("eventType must not be blank");
        }
        if (eventKey == null || eventKey.isBlank()) {
            throw NotificationException.validationFailed("eventKey must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw NotificationException.validationFailed("title must not be blank");
        }
        if (body == null || body.isBlank()) {
            throw NotificationException.validationFailed("body must not be blank");
        }

        // Deduplication: UNIQUE(user_id, event_key)
        Optional<Notification> existing = notificationRepository.findByUserIdAndEventKey(userId, eventKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        Notification notification = new Notification();
        notification.setWorkspaceId(workspaceId);
        notification.setUserId(userId);
        notification.setProjectId(projectId);
        notification.setEventType(eventType);
        notification.setEventKey(eventKey);
        notification.setTitle(title);
        notification.setBody(body);
        notification.setDeeplink(deeplink);
        notification.setEmailStatus(emailStatus != null ? emailStatus : Notification.EmailStatus.NOT_REQUESTED);
        notification.setVersion(0L);

        try {
            Notification saved = notificationRepository.save(notification);
            if (saved != null) {
                if (saved.getEmailStatus() == Notification.EmailStatus.PENDING) {
                    enqueueEmail(saved);
                }
                publishRealtimeNotification(saved);
            }
            return saved;
        } catch (DataIntegrityViolationException ex) {
            return notificationRepository.findByUserIdAndEventKey(userId, eventKey)
                    .orElseThrow(() -> ex);
        }
    }

    /**
     * Queues the e-mail copy in the same transaction as the notification (transactional outbox),
     * keyed by the notification id so retries never duplicate delivery.
     */
    private void enqueueEmail(Notification saved) {
        OutboxService outbox = outboxService == null ? null : outboxService.getIfAvailable();
        if (outbox == null || saved.getId() == null) {
            return;
        }
        outbox.enqueueJob(new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.NOTIFICATION_EMAIL,
                saved.getWorkspaceId(),
                saved.getProjectId(),
                null,
                OutboxService.idempotencyKey("notification-email", saved.getId().toString()),
                Map.of("notificationId", saved.getId().toString(), "eventType", saved.getEventType()),
                null));
    }

    private void publishRealtimeNotification(Notification saved) {
        RealtimeEventPublisher publisher = realtimeEventPublisher == null ? null : realtimeEventPublisher.getIfAvailable();
        if (publisher == null || saved.getUserId() == null) {
            return;
        }
        publisher.publishNotification(
                saved.getUserId(),
                saved.getWorkspaceId(),
                saved.getProjectId(),
                NotificationResponse.from(saved));
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> listNotifications(
            SessionService.Principal principal,
            boolean unreadOnly,
            Pageable pageable) {

        if (principal == null) {
            throw NotificationException.accessDenied("Authentication required");
        }
        accessPolicy.requireAuthenticatedUser(principal);

        Page<Notification> page = unreadOnly
                ? notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(principal.userId(), pageable)
                : notificationRepository.findByUserIdOrderByCreatedAtDesc(principal.userId(), pageable);

        return page.map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> listNotifications(
            SessionService.Principal principal,
            boolean unreadOnly) {

        if (principal == null) {
            throw NotificationException.accessDenied("Authentication required");
        }
        accessPolicy.requireAuthenticatedUser(principal);

        List<Notification> list = unreadOnly
                ? notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(principal.userId())
                : notificationRepository.findByUserIdOrderByCreatedAtDesc(principal.userId());

        return list.stream().map(NotificationResponse::from).toList();
    }

    public Notification markAsRead(SessionService.Principal principal, UUID notificationId) {
        if (principal == null) {
            throw NotificationException.accessDenied("Authentication required");
        }
        accessPolicy.requireAuthenticatedUser(principal);

        if (notificationId == null) {
            throw NotificationException.validationFailed("notificationId must not be null");
        }

        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> NotificationException.notificationNotFound("Notification not found: " + notificationId));

        if (!notification.getUserId().equals(principal.userId())) {
            throw NotificationException.accessDenied("Access denied to notification: " + notificationId);
        }

        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notification = notificationRepository.save(notification);
        }

        return notification;
    }

    public int markAllAsRead(SessionService.Principal principal) {
        if (principal == null) {
            throw NotificationException.accessDenied("Authentication required");
        }
        accessPolicy.requireAuthenticatedUser(principal);
        return notificationRepository.markAllAsRead(principal.userId(), Instant.now());
    }
}
