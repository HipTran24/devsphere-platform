package com.project.devsphere.realtime.application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Thread-safe registry tracking active WebSocket sessions by wsSessionId, userId,
 * authSessionId, and subscribed workspaceIds. Enforces per-user concurrent session
 * limits and supports immediate forced disconnection on membership removal or auth
 * session revocation.
 */
@Component
public class RealtimeSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSessionRegistry.class);

    private final int maxSessionsPerUser;
    private final ConcurrentHashMap<String, WebSocketSession> liveWebSocketSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionRecord> sessionsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> sessionIdsByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> sessionIdsByAuthSession = new ConcurrentHashMap<>();

    @Autowired
    public RealtimeSessionRegistry(
            @Value("${devsphere.realtime.max-sessions-per-user:10}") int maxSessionsPerUser) {
        if (maxSessionsPerUser < 1) {
            throw new IllegalArgumentException("maxSessionsPerUser must be at least 1");
        }
        this.maxSessionsPerUser = maxSessionsPerUser;
    }

    public int getMaxSessionsPerUser() {
        return maxSessionsPerUser;
    }

    public void registerWebSocketSession(WebSocketSession session) {
        if (session == null || session.getId() == null) {
            return;
        }
        liveWebSocketSessions.put(session.getId(), session);
    }

    public void unregisterWebSocketSession(String wsSessionId) {
        if (wsSessionId == null || wsSessionId.isBlank()) {
            return;
        }
        liveWebSocketSessions.remove(wsSessionId);
        unregisterSession(wsSessionId);
    }

    /**
     * Associates an authenticated STOMP session with its user and auth session ID,
     * atomically enforcing the configured max-sessions-per-user ceiling.
     */
    public void registerAuthenticatedSession(String wsSessionId, UUID userId, UUID authSessionId) {
        if (wsSessionId == null || wsSessionId.isBlank()) {
            throw new AccessDeniedException("Missing WebSocket session identifier");
        }
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(authSessionId, "authSessionId must not be null");

        sessionIdsByUser.compute(userId, (uid, existing) -> {
            Set<String> userSessions = existing != null ? existing : ConcurrentHashMap.newKeySet();
            if (!userSessions.contains(wsSessionId) && userSessions.size() >= maxSessionsPerUser) {
                throw new AccessDeniedException("Maximum concurrent real-time sessions exceeded");
            }
            userSessions.add(wsSessionId);
            SessionRecord record = new SessionRecord(wsSessionId, userId, authSessionId);
            SessionRecord previous = sessionsById.put(wsSessionId, record);
            if (previous != null && !previous.authSessionId().equals(authSessionId)) {
                removeFromIndex(sessionIdsByAuthSession, previous.authSessionId(), wsSessionId);
            }
            sessionIdsByAuthSession
                    .computeIfAbsent(authSessionId, ignored -> ConcurrentHashMap.newKeySet())
                    .add(wsSessionId);
            return userSessions;
        });
    }

    public void recordWorkspaceSubscription(String wsSessionId, UUID workspaceId) {
        recordWorkspaceSubscription(wsSessionId, null, workspaceId);
    }

    public void recordWorkspaceSubscription(String wsSessionId, String subscriptionId, UUID workspaceId) {
        if (wsSessionId == null || workspaceId == null) {
            return;
        }
        SessionRecord record = sessionsById.get(wsSessionId);
        if (record == null) {
            return;
        }
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            record.subscriptionsById().put(subscriptionId, workspaceId);
        } else {
            record.directWorkspaces().add(workspaceId);
        }
    }

    public void removeSubscription(String wsSessionId, String subscriptionId) {
        if (wsSessionId == null || subscriptionId == null) {
            return;
        }
        SessionRecord record = sessionsById.get(wsSessionId);
        if (record != null) {
            record.subscriptionsById().remove(subscriptionId);
        }
    }

    public void unregisterSession(String wsSessionId) {
        if (wsSessionId == null || wsSessionId.isBlank()) {
            return;
        }
        SessionRecord removed = sessionsById.remove(wsSessionId);
        if (removed != null) {
            removeFromIndex(sessionIdsByUser, removed.userId(), wsSessionId);
            removeFromIndex(sessionIdsByAuthSession, removed.authSessionId(), wsSessionId);
        }
    }

    /**
     * Immediately closes all active WebSocket sessions belonging to {@code userId} that hold
     * a subscription in {@code workspaceId} using {@link CloseStatus#POLICY_VIOLATION} (1008).
     */
    public int disconnectUserFromWorkspace(UUID workspaceId, UUID userId) {
        if (workspaceId == null || userId == null) {
            return 0;
        }
        Set<String> userSessions = sessionIdsByUser.get(userId);
        if (userSessions == null || userSessions.isEmpty()) {
            return 0;
        }

        List<String> matchingSessionIds = new ArrayList<>();
        for (String wsSessionId : List.copyOf(userSessions)) {
            SessionRecord record = sessionsById.get(wsSessionId);
            if (record != null && record.hasWorkspace(workspaceId)) {
                matchingSessionIds.add(wsSessionId);
            }
        }

        int closed = 0;
        for (String wsSessionId : matchingSessionIds) {
            if (closeAndUnregister(wsSessionId, CloseStatus.POLICY_VIOLATION)) {
                closed++;
            }
        }
        return closed;
    }

    /**
     * Immediately closes all active WebSocket sessions bound to the given {@code authSessionId}
     * using {@link CloseStatus#POLICY_VIOLATION} (1008).
     */
    public int disconnectAuthSession(UUID authSessionId) {
        if (authSessionId == null) {
            return 0;
        }
        Set<String> sessionIds = sessionIdsByAuthSession.get(authSessionId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }

        int closed = 0;
        for (String wsSessionId : List.copyOf(sessionIds)) {
            if (closeAndUnregister(wsSessionId, CloseStatus.POLICY_VIOLATION)) {
                closed++;
            }
        }
        return closed;
    }

    public int getActiveSessionCount(UUID userId) {
        if (userId == null) {
            return 0;
        }
        Set<String> ids = sessionIdsByUser.get(userId);
        return ids == null ? 0 : ids.size();
    }

    public boolean isSessionRegistered(String wsSessionId) {
        return wsSessionId != null && sessionsById.containsKey(wsSessionId);
    }

    public Set<UUID> getSubscribedWorkspaces(String wsSessionId) {
        SessionRecord record = wsSessionId == null ? null : sessionsById.get(wsSessionId);
        if (record == null) {
            return Set.of();
        }
        return record.allWorkspaces();
    }

    private boolean closeAndUnregister(String wsSessionId, CloseStatus closeStatus) {
        unregisterSession(wsSessionId);
        WebSocketSession wsSession = liveWebSocketSessions.remove(wsSessionId);
        if (wsSession == null) {
            return false;
        }
        if (wsSession.isOpen()) {
            try {
                wsSession.close(closeStatus);
                return true;
            } catch (IOException ex) {
                log.warn("Failed to close WebSocket session {} with status {}: {}",
                        wsSessionId, closeStatus.getCode(), ex.getMessage());
            }
        }
        return false;
    }

    private static void removeFromIndex(ConcurrentHashMap<UUID, Set<String>> index, UUID key, String wsSessionId) {
        if (key == null) {
            return;
        }
        index.computeIfPresent(key, (k, set) -> {
            set.remove(wsSessionId);
            return set.isEmpty() ? null : set;
        });
    }

    private static final class SessionRecord {
        private final String wsSessionId;
        private final UUID userId;
        private final UUID authSessionId;
        private final Set<UUID> directWorkspaces = ConcurrentHashMap.newKeySet();
        private final Map<String, UUID> subscriptionsById = new ConcurrentHashMap<>();

        private SessionRecord(String wsSessionId, UUID userId, UUID authSessionId) {
            this.wsSessionId = wsSessionId;
            this.userId = userId;
            this.authSessionId = authSessionId;
        }

        private String wsSessionId() {
            return wsSessionId;
        }

        private UUID userId() {
            return userId;
        }

        private UUID authSessionId() {
            return authSessionId;
        }

        private Set<UUID> directWorkspaces() {
            return directWorkspaces;
        }

        private Map<String, UUID> subscriptionsById() {
            return subscriptionsById;
        }

        private boolean hasWorkspace(UUID workspaceId) {
            return directWorkspaces.contains(workspaceId) || subscriptionsById.containsValue(workspaceId);
        }

        private Set<UUID> allWorkspaces() {
            Set<UUID> combined = new HashSet<>(directWorkspaces);
            combined.addAll(subscriptionsById.values());
            return Collections.unmodifiableSet(combined);
        }
    }
}
