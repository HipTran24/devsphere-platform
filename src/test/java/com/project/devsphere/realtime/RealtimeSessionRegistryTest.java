package com.project.devsphere.realtime;

import com.project.devsphere.realtime.application.RealtimeSessionRegistry;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeSessionRegistryTest {

    @Test
    @DisplayName("Enforces max-sessions-per-user limit of 10 and releases slots on unregister")
    void enforcesMaxSessionsPerUserLimit() {
        RealtimeSessionRegistry registry = new RealtimeSessionRegistry(10);
        UUID userId = UUID.randomUUID();
        UUID authSessionId = UUID.randomUUID();

        for (int i = 1; i <= 10; i++) {
            registry.registerAuthenticatedSession("ws-" + i, userId, authSessionId);
        }
        assertThat(registry.getActiveSessionCount(userId)).isEqualTo(10);

        assertThatThrownBy(() -> registry.registerAuthenticatedSession("ws-11", userId, authSessionId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Maximum concurrent real-time sessions exceeded");

        // Another user still has their own independent quota
        UUID otherUser = UUID.randomUUID();
        registry.registerAuthenticatedSession("other-ws-1", otherUser, UUID.randomUUID());
        assertThat(registry.getActiveSessionCount(otherUser)).isEqualTo(1);

        // Unregistering one session frees a slot for the original user
        registry.unregisterSession("ws-1");
        assertThat(registry.getActiveSessionCount(userId)).isEqualTo(9);

        registry.registerAuthenticatedSession("ws-11", userId, authSessionId);
        assertThat(registry.getActiveSessionCount(userId)).isEqualTo(10);
    }

    @Test
    @DisplayName("disconnectUserFromWorkspace closes only matching user+workspace sessions with POLICY_VIOLATION (1008)")
    void disconnectUserFromWorkspaceClosesMatchingSessionsWithPolicyViolation() throws IOException {
        RealtimeSessionRegistry registry = new RealtimeSessionRegistry(10);
        UUID workspaceA = UUID.randomUUID();
        UUID workspaceB = UUID.randomUUID();
        UUID targetUser = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();

        WebSocketSession targetWsA = mockOpenSession("ws-target-a");
        WebSocketSession targetWsB = mockOpenSession("ws-target-b");
        WebSocketSession otherWsA = mockOpenSession("ws-other-a");

        registry.registerWebSocketSession(targetWsA);
        registry.registerWebSocketSession(targetWsB);
        registry.registerWebSocketSession(otherWsA);

        registry.registerAuthenticatedSession("ws-target-a", targetUser, UUID.randomUUID());
        registry.registerAuthenticatedSession("ws-target-b", targetUser, UUID.randomUUID());
        registry.registerAuthenticatedSession("ws-other-a", otherUser, UUID.randomUUID());

        registry.recordWorkspaceSubscription("ws-target-a", "sub-1", workspaceA);
        registry.recordWorkspaceSubscription("ws-target-b", "sub-2", workspaceB);
        registry.recordWorkspaceSubscription("ws-other-a", "sub-3", workspaceA);

        int closed = registry.disconnectUserFromWorkspace(workspaceA, targetUser);

        assertThat(closed).isEqualTo(1);
        verify(targetWsA).close(CloseStatus.POLICY_VIOLATION);
        verify(targetWsB, never()).close(org.mockito.ArgumentMatchers.any());
        verify(otherWsA, never()).close(org.mockito.ArgumentMatchers.any());

        assertThat(registry.isSessionRegistered("ws-target-a")).isFalse();
        assertThat(registry.isSessionRegistered("ws-target-b")).isTrue();
        assertThat(registry.isSessionRegistered("ws-other-a")).isTrue();
        assertThat(registry.getActiveSessionCount(targetUser)).isEqualTo(1);
    }

    @Test
    @DisplayName("disconnectAuthSession closes all WebSocket sessions bound to that authSessionId")
    void disconnectAuthSessionClosesBoundSessions() throws IOException {
        RealtimeSessionRegistry registry = new RealtimeSessionRegistry(10);
        UUID userId = UUID.randomUUID();
        UUID revokedAuthSession = UUID.randomUUID();
        UUID activeAuthSession = UUID.randomUUID();

        WebSocketSession ws1 = mockOpenSession("ws-revoked-1");
        WebSocketSession ws2 = mockOpenSession("ws-revoked-2");
        WebSocketSession wsKeep = mockOpenSession("ws-active-1");

        registry.registerWebSocketSession(ws1);
        registry.registerWebSocketSession(ws2);
        registry.registerWebSocketSession(wsKeep);

        registry.registerAuthenticatedSession("ws-revoked-1", userId, revokedAuthSession);
        registry.registerAuthenticatedSession("ws-revoked-2", userId, revokedAuthSession);
        registry.registerAuthenticatedSession("ws-active-1", userId, activeAuthSession);

        int closed = registry.disconnectAuthSession(revokedAuthSession);

        assertThat(closed).isEqualTo(2);
        verify(ws1).close(CloseStatus.POLICY_VIOLATION);
        verify(ws2).close(CloseStatus.POLICY_VIOLATION);
        verify(wsKeep, never()).close(org.mockito.ArgumentMatchers.any());

        assertThat(registry.isSessionRegistered("ws-revoked-1")).isFalse();
        assertThat(registry.isSessionRegistered("ws-revoked-2")).isFalse();
        assertThat(registry.isSessionRegistered("ws-active-1")).isTrue();
    }

    @Test
    @DisplayName("removeSubscription clears workspace association before disconnect check")
    void removeSubscriptionClearsWorkspaceAssociation() throws IOException {
        RealtimeSessionRegistry registry = new RealtimeSessionRegistry(10);
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        WebSocketSession ws = mockOpenSession("ws-sub-test");
        registry.registerWebSocketSession(ws);
        registry.registerAuthenticatedSession("ws-sub-test", userId, UUID.randomUUID());
        registry.recordWorkspaceSubscription("ws-sub-test", "sub-chat", workspaceId);
        assertThat(registry.getSubscribedWorkspaces("ws-sub-test")).containsExactly(workspaceId);

        registry.removeSubscription("ws-sub-test", "sub-chat");
        assertThat(registry.getSubscribedWorkspaces("ws-sub-test")).isEmpty();

        int closed = registry.disconnectUserFromWorkspace(workspaceId, userId);
        assertThat(closed).isZero();
        verify(ws, never()).close(org.mockito.ArgumentMatchers.any());
    }

    private static WebSocketSession mockOpenSession(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
