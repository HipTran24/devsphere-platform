package com.project.devsphere.realtime.security;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.realtime.application.RealtimeSessionRegistry;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Enforces Bearer JWT authentication on STOMP CONNECT and live DB-backed RBAC on
 * every STOMP SUBSCRIBE frame (default-deny, zero positive caching).
 */
@Component
public class StompJwtChannelInterceptor implements ChannelInterceptor {

    public static final String SESSION_ATTR_PRINCIPAL = "devsphere.realtime.principal";
    public static final String SESSION_ATTR_AUTH_SESSION_ID = "devsphere.realtime.authSessionId";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USER_NOTIFICATIONS_DESTINATION = "/user/queue/notifications";
    private static final String UUID_GROUP = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";

    private static final Pattern CHAT_TOPIC_PATTERN = Pattern.compile(
            "^/topic/workspaces/" + UUID_GROUP + "/projects/" + UUID_GROUP + "/chat/" + UUID_GROUP + "$");
    private static final Pattern CONTROL_TOWER_TOPIC_PATTERN = Pattern.compile(
            "^/topic/workspaces/" + UUID_GROUP + "/projects/" + UUID_GROUP + "/control-tower$");

    private static final Set<String> FORBIDDEN_TOKEN_HEADERS = Set.of("token", "access_token");
    private static final Set<String> INTERNAL_CHANNEL_TYPES = Set.of("TEAM", "TASK", "PROJECT");
    private static final Set<ProjectRole> INTERNAL_PROJECT_ROLES = Set.of(
            ProjectRole.PM,
            ProjectRole.DEVELOPER,
            ProjectRole.VIEWER
    );

    private final SessionService sessionService;
    private final AccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;
    private final RealtimeSessionRegistry sessionRegistry;

    public StompJwtChannelInterceptor(
            SessionService sessionService,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate,
            RealtimeSessionRegistry sessionRegistry) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry must not be null");
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        return switch (command) {
            case CONNECT -> handleConnect(message, accessor);
            case SUBSCRIBE -> {
                handleSubscribe(accessor);
                yield message;
            }
            case UNSUBSCRIBE -> {
                handleUnsubscribe(accessor);
                yield message;
            }
            case DISCONNECT -> {
                handleDisconnect(accessor);
                yield message;
            }
            case SEND -> {
                if (resolvePrincipal(accessor) == null) {
                    throw new AccessDeniedException("Authentication required");
                }
                yield message;
            }
            default -> message;
        };
    }

    private Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null && Boolean.TRUE.equals(
                sessionAttributes.get(QueryParamRejectHandshakeInterceptor.QUERY_TOKEN_REJECTED_ATTR))) {
            throw new AccessDeniedException("Query-parameter token authentication is not permitted");
        }

        rejectForbiddenTokenNativeHeaders(accessor);

        String rawToken = extractBearerToken(accessor);
        SessionService.Principal principal = sessionService.authenticate(rawToken)
                .orElseThrow(() -> new AccessDeniedException("Invalid or expired authentication token"));

        String wsSessionId = accessor.getSessionId();
        if (wsSessionId == null || wsSessionId.isBlank()) {
            throw new AccessDeniedException("Missing WebSocket session identifier");
        }

        sessionRegistry.registerAuthenticatedSession(wsSessionId, principal.userId(), principal.sessionId());

        StompUserPrincipal stompUser = new StompUserPrincipal(principal);
        if (sessionAttributes != null) {
            sessionAttributes.put(SESSION_ATTR_PRINCIPAL, principal);
            sessionAttributes.put(SESSION_ATTR_AUTH_SESSION_ID, principal.sessionId());
        }

        if (accessor.isMutable()) {
            accessor.setUser(stompUser);
            return message;
        }

        StompHeaderAccessor mutable = StompHeaderAccessor.wrap(message);
        mutable.setUser(stompUser);
        return MessageBuilder.createMessage(message.getPayload(), mutable.getMessageHeaders());
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        SessionService.Principal principal = resolvePrincipal(accessor);
        if (principal == null) {
            throw new AccessDeniedException("Authentication required");
        }

        String destination = accessor.getDestination();
        if (destination == null || destination.isBlank()) {
            throw new AccessDeniedException("Subscription destination required");
        }

        if (USER_NOTIFICATIONS_DESTINATION.equals(destination)) {
            accessPolicy.requireAuthenticatedUser(principal);
            return;
        }

        Matcher chatMatcher = CHAT_TOPIC_PATTERN.matcher(destination);
        if (chatMatcher.matches()) {
            UUID workspaceId = parseUuid(chatMatcher.group(1));
            UUID projectId = parseUuid(chatMatcher.group(2));
            UUID channelId = parseUuid(chatMatcher.group(3));

            authorizeChatChannelSubscription(principal, workspaceId, projectId, channelId);
            sessionRegistry.recordWorkspaceSubscription(accessor.getSessionId(), accessor.getSubscriptionId(), workspaceId);
            return;
        }

        Matcher controlTowerMatcher = CONTROL_TOWER_TOPIC_PATTERN.matcher(destination);
        if (controlTowerMatcher.matches()) {
            UUID workspaceId = parseUuid(controlTowerMatcher.group(1));
            UUID projectId = parseUuid(controlTowerMatcher.group(2));

            accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, INTERNAL_PROJECT_ROLES);
            sessionRegistry.recordWorkspaceSubscription(accessor.getSessionId(), accessor.getSubscriptionId(), workspaceId);
            return;
        }

        throw new AccessDeniedException("Subscription destination not permitted");
    }

    private void authorizeChatChannelSubscription(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID channelId) {

        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        String sql = """
                SELECT c.type
                FROM chat_channels c
                JOIN chat_channel_members m
                  ON m.channel_id = c.id
                 AND m.workspace_id = c.workspace_id
                 AND m.project_id = c.project_id
                WHERE c.id = ?
                  AND c.workspace_id = ?
                  AND c.project_id = ?
                  AND c.archived_at IS NULL
                  AND m.user_id = ?
                """;

        List<String> channelTypes = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getString("type"),
                channelId,
                workspaceId,
                projectId,
                principal.userId()
        );

        if (channelTypes.isEmpty()) {
            throw new AccessDeniedException("Caller is not an active member of chat channel");
        }

        String channelType = channelTypes.get(0);
        if (channelType == null) {
            throw new AccessDeniedException("Invalid chat channel type");
        }
        String normalizedType = channelType.trim().toUpperCase(Locale.ROOT);

        if (INTERNAL_CHANNEL_TYPES.contains(normalizedType)) {
            if (!scope.hasAnyRole(INTERNAL_PROJECT_ROLES)) {
                throw new AccessDeniedException("CLIENT-only member cannot subscribe to internal channel");
            }
        } else if ("CLIENT_PM".equals(normalizedType)) {
            if (!scope.hasRole(ProjectRole.PM) && !scope.hasRole(ProjectRole.CLIENT)) {
                throw new AccessDeniedException("CLIENT_PM channel requires CLIENT or PM role");
            }
        }
    }

    private void handleUnsubscribe(StompHeaderAccessor accessor) {
        if (accessor.getSessionId() != null && accessor.getSubscriptionId() != null) {
            sessionRegistry.removeSubscription(accessor.getSessionId(), accessor.getSubscriptionId());
        }
    }

    private void handleDisconnect(StompHeaderAccessor accessor) {
        if (accessor.getSessionId() != null) {
            sessionRegistry.unregisterSession(accessor.getSessionId());
        }
    }

    private static void rejectForbiddenTokenNativeHeaders(StompHeaderAccessor accessor) {
        Map<String, List<String>> nativeHeaders = accessor.toNativeHeaderMap();
        for (String headerName : nativeHeaders.keySet()) {
            if (headerName != null && FORBIDDEN_TOKEN_HEADERS.contains(headerName.trim().toLowerCase(Locale.ROOT))) {
                throw new AccessDeniedException("Token must be supplied via Authorization Bearer header only");
            }
        }
    }

    private static String extractBearerToken(StompHeaderAccessor accessor) {
        Map<String, List<String>> nativeHeaders = accessor.toNativeHeaderMap();
        List<String> authValues = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : nativeHeaders.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(HttpHeaders.AUTHORIZATION)
                    && entry.getValue() != null) {
                authValues.addAll(entry.getValue());
            }
        }
        if (authValues.size() != 1) {
            throw new AccessDeniedException("Authorization Bearer header required");
        }
        String header = authValues.get(0);
        if (header == null) {
            throw new AccessDeniedException("Authorization Bearer header required");
        }
        String trimmed = header.trim();
        if (!trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new AccessDeniedException("Authorization header must use Bearer scheme");
        }
        String token = trimmed.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty() || token.contains(" ") || token.contains(",")) {
            throw new AccessDeniedException("Malformed Bearer token");
        }
        return token;
    }

    private static SessionService.Principal resolvePrincipal(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof StompUserPrincipal stompUser) {
            return stompUser.principal();
        }
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs != null) {
            Object stored = attrs.get(SESSION_ATTR_PRINCIPAL);
            if (stored instanceof SessionService.Principal principal) {
                return principal;
            }
        }
        return null;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("Invalid destination identifier");
        }
    }

    /**
     * STOMP {@link Principal} wrapper whose {@link #getName()} equals the authenticated
     * user's UUID string, matching {@code /user/{userId}/queue/...} routing.
     */
    public record StompUserPrincipal(SessionService.Principal principal) implements Principal {

        public StompUserPrincipal {
            Objects.requireNonNull(principal, "principal must not be null");
            Objects.requireNonNull(principal.userId(), "principal.userId must not be null");
        }

        @Override
        public String getName() {
            return principal.userId().toString();
        }
    }
}
