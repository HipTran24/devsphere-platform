package com.project.devsphere.realtime;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.realtime.application.RealtimeSessionRegistry;
import com.project.devsphere.realtime.security.QueryParamRejectHandshakeInterceptor;
import com.project.devsphere.realtime.security.StompJwtChannelInterceptor;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.WebSocketHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompJwtChannelInterceptorTest {

    @Mock private SessionService sessionService;
    @Mock private AccessPolicy accessPolicy;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private MessageChannel channel;

    private RealtimeSessionRegistry sessionRegistry;
    private StompJwtChannelInterceptor interceptor;
    private QueryParamRejectHandshakeInterceptor handshakeInterceptor;

    private UUID userId;
    private UUID authSessionId;
    private UUID workspaceId;
    private UUID projectId;
    private UUID channelId;
    private SessionService.Principal principal;

    @BeforeEach
    void setUp() {
        sessionRegistry = new RealtimeSessionRegistry(10);
        interceptor = new StompJwtChannelInterceptor(sessionService, accessPolicy, jdbcTemplate, sessionRegistry);
        handshakeInterceptor = new QueryParamRejectHandshakeInterceptor();

        userId = UUID.randomUUID();
        authSessionId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        channelId = UUID.randomUUID();
        principal = new SessionService.Principal(userId, authSessionId, "Alice", "USER");
    }

    @Test
    @DisplayName("CONNECT with valid Bearer token authenticates, sets Principal name to userId, and registers session")
    void connectWithValidBearerTokenSucceeds() {
        when(sessionService.authenticate("valid-jwt-token")).thenReturn(Optional.of(principal));

        Map<String, Object> attrs = new HashMap<>();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("ws-session-1");
        accessor.setSessionAttributes(attrs);
        accessor.setNativeHeader("Authorization", "Bearer valid-jwt-token");
        accessor.setLeaveMutable(true);

        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNotNull();
        StompHeaderAccessor out = StompHeaderAccessor.wrap(result);
        assertThat(out.getUser()).isNotNull();
        assertThat(out.getUser().getName()).isEqualTo(userId.toString());
        assertThat(attrs.get(StompJwtChannelInterceptor.SESSION_ATTR_PRINCIPAL)).isEqualTo(principal);
        assertThat(attrs.get(StompJwtChannelInterceptor.SESSION_ATTR_AUTH_SESSION_ID)).isEqualTo(authSessionId);
        assertThat(sessionRegistry.isSessionRegistered("ws-session-1")).isTrue();
    }

    @Test
    @DisplayName("CONNECT rejects missing Authorization header, non-Bearer scheme, and invalid tokens")
    void connectRejectsMissingOrInvalidTokens() {
        StompHeaderAccessor missingAuth = StompHeaderAccessor.create(StompCommand.CONNECT);
        missingAuth.setSessionId("ws-missing");
        assertThatThrownBy(() -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], missingAuth.getMessageHeaders()), channel))
                .isInstanceOf(AccessDeniedException.class);

        StompHeaderAccessor basicAuth = StompHeaderAccessor.create(StompCommand.CONNECT);
        basicAuth.setSessionId("ws-basic");
        basicAuth.setNativeHeader("Authorization", "Basic dXNlcjpwYXNz");
        assertThatThrownBy(() -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], basicAuth.getMessageHeaders()), channel))
                .isInstanceOf(AccessDeniedException.class);

        when(sessionService.authenticate("expired-token")).thenReturn(Optional.empty());
        StompHeaderAccessor invalidToken = StompHeaderAccessor.create(StompCommand.CONNECT);
        invalidToken.setSessionId("ws-invalid");
        invalidToken.setNativeHeader("Authorization", "Bearer expired-token");
        assertThatThrownBy(() -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], invalidToken.getMessageHeaders()), channel))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(sessionRegistry.isSessionRegistered("ws-invalid")).isFalse();
    }

    @Test
    @DisplayName("Handshake and CONNECT reject query-parameter tokens (?token= / ?access_token=)")
    void rejectsQueryParameterTokensAtHandshakeAndConnect() {
        for (String uri : List.of("ws://localhost:8080/ws?token=secret", "ws://localhost:8080/ws?foo=bar&access_token=secret")) {
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            ServerHttpResponse response = mock(ServerHttpResponse.class);
            when(request.getURI()).thenReturn(URI.create(uri));
            Map<String, Object> attrs = new HashMap<>();

            boolean allowed = handshakeInterceptor.beforeHandshake(request, response, mock(WebSocketHandler.class), attrs);

            assertThat(allowed).isFalse();
            verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
            assertThat(attrs.get(QueryParamRejectHandshakeInterceptor.QUERY_TOKEN_REJECTED_ATTR)).isEqualTo(Boolean.TRUE);
        }

        // Clean handshake without query token is allowed
        ServerHttpRequest cleanRequest = mock(ServerHttpRequest.class);
        ServerHttpResponse cleanResponse = mock(ServerHttpResponse.class);
        when(cleanRequest.getURI()).thenReturn(URI.create("ws://localhost:8080/ws"));
        assertThat(handshakeInterceptor.beforeHandshake(cleanRequest, cleanResponse, mock(WebSocketHandler.class), new HashMap<>())).isTrue();

        // Defense-in-depth on CONNECT if query-token marker or forbidden native header is present
        StompHeaderAccessor queryMarked = StompHeaderAccessor.create(StompCommand.CONNECT);
        queryMarked.setSessionId("ws-query");
        queryMarked.setSessionAttributes(Map.of(QueryParamRejectHandshakeInterceptor.QUERY_TOKEN_REJECTED_ATTR, Boolean.TRUE));
        queryMarked.setNativeHeader("Authorization", "Bearer valid-jwt-token");
        assertThatThrownBy(() -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], queryMarked.getMessageHeaders()), channel))
                .isInstanceOf(AccessDeniedException.class);

        StompHeaderAccessor tokenHeader = StompHeaderAccessor.create(StompCommand.CONNECT);
        tokenHeader.setSessionId("ws-token-hdr");
        tokenHeader.setNativeHeader("token", "some-token");
        assertThatThrownBy(() -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], tokenHeader.getMessageHeaders()), channel))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("SUBSCRIBE to /user/queue/notifications verifies authenticated user")
    void subscribeNotificationsVerifiesAuthenticatedUser() {
        Message<byte[]> subscribe = subscribeMessage("ws-1", "sub-notif", "/user/queue/notifications");

        interceptor.preSend(subscribe, channel);

        verify(accessPolicy).requireAuthenticatedUser(principal);
    }

    @Test
    @DisplayName("SUBSCRIBE to chat topic allows active internal member and records workspace subscription")
    void subscribeChatAllowsActiveInternalMember() {
        sessionRegistry.registerAuthenticatedSession("ws-1", userId, authSessionId);
        when(accessPolicy.requireProjectMember(principal, workspaceId, projectId))
                .thenReturn(new ProjectScope(workspaceId, projectId, userId, Set.of(ProjectRole.DEVELOPER)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(channelId), eq(workspaceId), eq(projectId), eq(userId)))
                .thenReturn(List.of("TEAM"));

        String dest = "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/chat/" + channelId;
        interceptor.preSend(subscribeMessage("ws-1", "sub-chat", dest), channel);

        assertThat(sessionRegistry.getSubscribedWorkspaces("ws-1")).containsExactly(workspaceId);
    }

    @Test
    @DisplayName("SUBSCRIBE to internal chat channel denies CLIENT-only member and non-members")
    void subscribeChatDeniesClientOnlyOnInternalChannelAndNonMembers() {
        String dest = "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/chat/" + channelId;

        // CLIENT-only on TEAM channel -> denied
        when(accessPolicy.requireProjectMember(principal, workspaceId, projectId))
                .thenReturn(new ProjectScope(workspaceId, projectId, userId, Set.of(ProjectRole.CLIENT)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(channelId), eq(workspaceId), eq(projectId), eq(userId)))
                .thenReturn(List.of("TEAM"));

        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("ws-1", "sub-chat", dest), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("CLIENT-only");

        // CLIENT on CLIENT_PM channel -> allowed
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(channelId), eq(workspaceId), eq(projectId), eq(userId)))
                .thenReturn(List.of("CLIENT_PM"));
        sessionRegistry.registerAuthenticatedSession("ws-client", userId, authSessionId);
        interceptor.preSend(subscribeMessage("ws-client", "sub-client-pm", dest), channel);
        assertThat(sessionRegistry.getSubscribedWorkspaces("ws-client")).containsExactly(workspaceId);

        // Not a member of channel -> denied
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(channelId), eq(workspaceId), eq(projectId), eq(userId)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("ws-1", "sub-chat", dest), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not an active member");
    }

    @Test
    @DisplayName("SUBSCRIBE to control-tower enforces internal roles (PM/DEVELOPER/VIEWER) and blocks CLIENT-only")
    void subscribeControlTowerEnforcesInternalRoles() {
        sessionRegistry.registerAuthenticatedSession("ws-dev", userId, authSessionId);
        String dest = "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/control-tower";
        Set<ProjectRole> internalRoles = Set.of(ProjectRole.PM, ProjectRole.DEVELOPER, ProjectRole.VIEWER);

        when(accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, internalRoles))
                .thenReturn(new ProjectScope(workspaceId, projectId, userId, Set.of(ProjectRole.VIEWER)));

        interceptor.preSend(subscribeMessage("ws-dev", "sub-ct", dest), channel);
        assertThat(sessionRegistry.getSubscribedWorkspaces("ws-dev")).containsExactly(workspaceId);

        doThrow(new AccessDeniedException("Access denied"))
                .when(accessPolicy).requireAnyProjectRole(principal, workspaceId, projectId, internalRoles);

        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("ws-client", "sub-ct", dest), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("SUBSCRIBE to unknown or cross-user destinations is default-denied")
    void subscribeUnknownDestinationIsDefaultDenied() {
        for (String dest : List.of(
                "/topic/global",
                "/queue/notifications",
                "/user/" + UUID.randomUUID() + "/queue/notifications",
                "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/secrets",
                "/topic/workspaces/not-a-uuid/projects/" + projectId + "/control-tower")) {
            assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("ws-1", "sub-x", dest), channel))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    @DisplayName("DISCONNECT unregisters session from RealtimeSessionRegistry")
    void disconnectUnregistersSession() {
        sessionRegistry.registerAuthenticatedSession("ws-disc", userId, authSessionId);
        assertThat(sessionRegistry.isSessionRegistered("ws-disc")).isTrue();

        StompHeaderAccessor disconnect = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        disconnect.setSessionId("ws-disc");
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], disconnect.getMessageHeaders()), channel);

        assertThat(sessionRegistry.isSessionRegistered("ws-disc")).isFalse();
    }

    @Test
    @DisplayName("SEND frames are default-denied even for authenticated sessions")
    void sendFramesAreDefaultDenied() {
        StompHeaderAccessor send = StompHeaderAccessor.create(StompCommand.SEND);
        send.setSessionId("ws-auth");
        send.setDestination("/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/chat/" + channelId);
        send.setUser(new StompJwtChannelInterceptor.StompUserPrincipal(principal));

        assertThatThrownBy(() -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], send.getMessageHeaders()), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SEND");
    }

    @Test
    @DisplayName("STOMP 1.2 STOMP command enforces Bearer auth and session limits identically to CONNECT")
    void stomp12CommandAuthenticatesAndEnforcesSessionLimits() {
        RealtimeSessionRegistry tightRegistry = new RealtimeSessionRegistry(1);
        StompJwtChannelInterceptor tightInterceptor = new StompJwtChannelInterceptor(
                sessionService, accessPolicy, jdbcTemplate, tightRegistry);

        // Unauthenticated STOMP frame rejected
        StompHeaderAccessor unauth = StompHeaderAccessor.create(StompCommand.STOMP);
        unauth.setSessionId("ws-stomp-unauth");
        assertThatThrownBy(() -> tightInterceptor.preSend(
                MessageBuilder.createMessage(new byte[0], unauth.getMessageHeaders()), channel))
                .isInstanceOf(AccessDeniedException.class);

        // Valid STOMP frame authenticated and registered
        when(sessionService.authenticate("valid-stomp-token")).thenReturn(Optional.of(principal));
        Map<String, Object> attrs = new HashMap<>();
        StompHeaderAccessor valid = StompHeaderAccessor.create(StompCommand.STOMP);
        valid.setSessionId("ws-stomp-1");
        valid.setSessionAttributes(attrs);
        valid.setNativeHeader("Authorization", "Bearer valid-stomp-token");
        valid.setLeaveMutable(true);

        Message<?> result = tightInterceptor.preSend(
                MessageBuilder.createMessage(new byte[0], valid.getMessageHeaders()), channel);
        StompHeaderAccessor out = StompHeaderAccessor.wrap(result);
        assertThat(out.getUser()).isNotNull();
        assertThat(out.getUser().getName()).isEqualTo(userId.toString());
        assertThat(tightRegistry.isSessionRegistered("ws-stomp-1")).isTrue();

        // Second STOMP frame for same user hits max-sessions-per-user limit
        StompHeaderAccessor second = StompHeaderAccessor.create(StompCommand.STOMP);
        second.setSessionId("ws-stomp-2");
        second.setNativeHeader("Authorization", "Bearer valid-stomp-token");
        assertThatThrownBy(() -> tightInterceptor.preSend(
                MessageBuilder.createMessage(new byte[0], second.getMessageHeaders()), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Maximum concurrent real-time sessions exceeded");
    }

    @Test
    @DisplayName("Unexpected STOMP client commands (BEGIN/COMMIT/ABORT) are rejected while heartbeats and authenticated ACK/NACK pass")
    void unexpectedCommandsRejectedWhileHeartbeatAndAuthAckPass() {
        for (StompCommand cmd : List.of(StompCommand.BEGIN, StompCommand.COMMIT, StompCommand.ABORT)) {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(cmd);
            accessor.setSessionId("ws-1");
            accessor.setUser(new StompJwtChannelInterceptor.StompUserPrincipal(principal));
            assertThatThrownBy(() -> interceptor.preSend(
                    MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), channel))
                    .isInstanceOf(AccessDeniedException.class);
        }

        // Unauthenticated ACK/NACK rejected
        for (StompCommand cmd : List.of(StompCommand.ACK, StompCommand.NACK)) {
            StompHeaderAccessor unauth = StompHeaderAccessor.create(cmd);
            unauth.setSessionId("ws-1");
            assertThatThrownBy(() -> interceptor.preSend(
                    MessageBuilder.createMessage(new byte[0], unauth.getMessageHeaders()), channel))
                    .isInstanceOf(AccessDeniedException.class);

            StompHeaderAccessor auth = StompHeaderAccessor.create(cmd);
            auth.setSessionId("ws-1");
            auth.setUser(new StompJwtChannelInterceptor.StompUserPrincipal(principal));
            assertThat(interceptor.preSend(MessageBuilder.createMessage(new byte[0], auth.getMessageHeaders()), channel))
                    .isNotNull();
        }

        // Heartbeat (no STOMP command) passes through
        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();
        Message<byte[]> hbMsg = MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders());
        assertThat(interceptor.preSend(hbMsg, channel)).isSameAs(hbMsg);
    }

    @Test
    @DisplayName("SUBSCRIBE to chat channel with unknown channel type is strictly default-denied")
    void subscribeUnknownChatChannelTypeIsDenied() {
        String dest = "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/chat/" + channelId;
        when(accessPolicy.requireProjectMember(principal, workspaceId, projectId))
                .thenReturn(new ProjectScope(workspaceId, projectId, userId, Set.of(ProjectRole.PM)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(channelId), eq(workspaceId), eq(projectId), eq(userId)))
                .thenReturn(List.of("UNKNOWN_CUSTOM_TYPE"));

        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage("ws-1", "sub-unknown-type", dest), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Unsupported chat channel type");
    }

    @Test
    @DisplayName("Handshake rejects malformed percent-encoding (?foo%ZZ=1) with 401 instead of throwing 500")
    void handshakeRejectsMalformedPercentEscapeWith401() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        URI malformedUri = mock(URI.class);
        when(malformedUri.getRawQuery()).thenReturn("foo%ZZ=1");
        when(request.getURI()).thenReturn(malformedUri);

        Map<String, Object> attrs = new HashMap<>();
        boolean allowed = handshakeInterceptor.beforeHandshake(request, response, mock(WebSocketHandler.class), attrs);

        assertThat(allowed).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    private Message<byte[]> subscribeMessage(String sessionId, String subId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subId);
        accessor.setDestination(destination);
        accessor.setUser(new StompJwtChannelInterceptor.StompUserPrincipal(principal));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
