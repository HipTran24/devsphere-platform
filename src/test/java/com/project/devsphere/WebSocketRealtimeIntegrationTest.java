package com.project.devsphere;

import com.project.devsphere.communication.application.ChatService;
import com.project.devsphere.communication.domain.ChatChannel;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.monitoring.api.dto.UpdateAlertStatusRequest;
import com.project.devsphere.monitoring.application.AlertService;
import com.project.devsphere.monitoring.domain.Alert;
import com.project.devsphere.notification.application.NotificationService;
import com.project.devsphere.workspace.application.WorkspaceMembershipService;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "devsphere.database.manage-runtime-privileges=false",
                "devsphere.auth.mail.from=devsphere@example.test",
                "spring.mail.host=localhost",
                "spring.mail.port=2525",
                "devsphere.auth.mail.initial-delay-ms=600000"
        }
)
class WebSocketRealtimeIntegrationTest {

    private static final String TEST_PASSWORD = "A strong integration test password";

    @LocalServerPort
    private int port;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionService sessionService;
    @Autowired private ChatService chatService;
    @Autowired private NotificationService notificationService;
    @Autowired private AlertService alertService;
    @Autowired private WorkspaceMembershipService workspaceMembershipService;
    @MockitoBean private JavaMailSender mailSender;

    private final JsonMapper jsonMapper = new JsonMapper();
    private final Argon2PasswordEncoder passwordEncoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
    }

    @BeforeEach
    void cleanDatabase() {
        var tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class
        );
        jdbc.execute("TRUNCATE TABLE " + String.join(",", tables) + " CASCADE");
    }

    private record Fixture(
            UUID workspaceId,
            UUID projectId,
            UUID ownerId,
            UUID devId,
            UUID clientId,
            String ownerToken,
            String devToken,
            String clientToken,
            SessionService.Principal ownerPrincipal,
            SessionService.Principal devPrincipal,
            SessionService.Principal clientPrincipal
    ) {}

    private Fixture createFixture() {
        UUID ownerId = createUser("owner-" + UUID.randomUUID() + "@example.test", "Owner");
        UUID devId = createUser("dev-" + UUID.randomUUID() + "@example.test", "Dev");
        UUID clientId = createUser("client-" + UUID.randomUUID() + "@example.test", "Client");

        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            jdbc.update(
                    "INSERT INTO workspaces(id, slug, name, owner_id) VALUES (?, ?, 'Realtime WS', ?)",
                    workspaceId, "ws-" + workspaceId, ownerId);
            jdbc.update(
                    "INSERT INTO workspace_members(workspace_id, user_id, role, status) VALUES (?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'MEMBER', 'ACTIVE'), (?, ?, 'MEMBER', 'ACTIVE')",
                    workspaceId, ownerId, workspaceId, devId, workspaceId, clientId);
            jdbc.update(
                    "INSERT INTO projects(id, workspace_id, project_key, name, objective, planned_start) VALUES (?, ?, 'RT', 'Realtime Project', 'Live sync', current_date)",
                    projectId, workspaceId);

            UUID ownerPm = UUID.randomUUID();
            UUID devPm = UUID.randomUUID();
            UUID clientPm = UUID.randomUUID();

            jdbc.update(
                    "INSERT INTO project_members(id, project_id, workspace_id, user_id, status) VALUES (?, ?, ?, ?, 'ACTIVE'), (?, ?, ?, ?, 'ACTIVE'), (?, ?, ?, ?, 'ACTIVE')",
                    ownerPm, projectId, workspaceId, ownerId,
                    devPm, projectId, workspaceId, devId,
                    clientPm, projectId, workspaceId, clientId);

            jdbc.update(
                    "INSERT INTO project_member_roles(project_id, project_member_id, role, granted_by, accepted_by, accepted_at) VALUES (?, ?, 'PM', ?, ?, now())",
                    projectId, ownerPm, ownerId, ownerId);
            jdbc.update(
                    "INSERT INTO project_member_roles(project_id, project_member_id, role, granted_by) VALUES (?, ?, 'DEVELOPER', ?)",
                    projectId, devPm, ownerId);
            jdbc.update(
                    "INSERT INTO project_member_roles(project_id, project_member_id, role, granted_by) VALUES (?, ?, 'CLIENT', ?)",
                    projectId, clientPm, ownerId);
        });

        String ownerEmail = jdbc.queryForObject("SELECT email::text FROM users WHERE id = ?", String.class, ownerId);
        String devEmail = jdbc.queryForObject("SELECT email::text FROM users WHERE id = ?", String.class, devId);
        String clientEmail = jdbc.queryForObject("SELECT email::text FROM users WHERE id = ?", String.class, clientId);

        String ownerToken = sessionService.login(ownerEmail, TEST_PASSWORD).access().value();
        String devToken = sessionService.login(devEmail, TEST_PASSWORD).access().value();
        String clientToken = sessionService.login(clientEmail, TEST_PASSWORD).access().value();

        return new Fixture(
                workspaceId,
                projectId,
                ownerId,
                devId,
                clientId,
                ownerToken,
                devToken,
                clientToken,
                sessionService.authenticate(ownerToken).orElseThrow(),
                sessionService.authenticate(devToken).orElseThrow(),
                sessionService.authenticate(clientToken).orElseThrow()
        );
    }

    private UUID createUser(String email, String displayName) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO users(id, email, display_name, password_hash, system_role, status, email_verified_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, 'USER', 'ACTIVE', ?, ?, ?, 0)",
                id, email, displayName, passwordEncoder.encode(TEST_PASSWORD), now, now, now
        );
        return id;
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    @Test
    @DisplayName("Rejects WebSocket handshake with ?token= or ?access_token= query params and rejects unauthenticated STOMP CONNECT")
    void rejectsQueryParamTokensAndInvalidStompConnect() {
        Fixture f = createFixture();
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());

        try {
            // 1. Query param ?token=... rejected at HTTP handshake
            StompHeaders authHeaders = new StompHeaders();
            authHeaders.add("Authorization", "Bearer " + f.devToken());
            assertThatThrownBy(() -> client.connectAsync(
                    wsUrl() + "?token=" + f.devToken(),
                    new WebSocketHttpHeaders(),
                    authHeaders,
                    new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS)).isInstanceOf(Exception.class);

            // 2. Query param ?access_token=... rejected at HTTP handshake
            assertThatThrownBy(() -> client.connectAsync(
                    wsUrl() + "?access_token=" + f.devToken(),
                    new WebSocketHttpHeaders(),
                    authHeaders,
                    new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS)).isInstanceOf(Exception.class);

            // 3. STOMP CONNECT without Authorization header fails
            assertThatThrownBy(() -> client.connectAsync(
                    wsUrl(),
                    new WebSocketHttpHeaders(),
                    new StompHeaders(),
                    new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS)).isInstanceOf(Exception.class);
        } finally {
            client.stop();
        }
    }

    @Test
    @DisplayName("Delivers live CHAT_MESSAGE_CREATED, NOTIFICATION_CREATED, and Control Tower ALERT events after commit")
    void deliversLiveChatNotificationAndAlertEvents() throws Exception {
        Fixture f = createFixture();
        ChatChannel teamChannel = chatService.createChannel(
                f.ownerPrincipal(), f.workspaceId(), f.projectId(), ChatChannel.Type.TEAM, "engineering", null);
        chatService.addMember(f.ownerPrincipal(), f.workspaceId(), f.projectId(), teamChannel.getId(), f.devId());

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());

        try {
            StompHeaders devHeaders = new StompHeaders();
            devHeaders.add("Authorization", "Bearer " + f.devToken());

            StompSession devSession = client.connectAsync(
                    wsUrl(),
                    new WebSocketHttpHeaders(),
                    devHeaders,
                    new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS);

            BlockingQueue<String> chatFrames = new LinkedBlockingQueue<>();
            BlockingQueue<String> notifFrames = new LinkedBlockingQueue<>();
            BlockingQueue<String> controlTowerFrames = new LinkedBlockingQueue<>();

            String chatDest = "/topic/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/chat/" + teamChannel.getId();
            String controlTowerDest = "/topic/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/control-tower";

            devSession.subscribe(chatDest, new QueueFrameHandler(chatFrames));
            devSession.subscribe("/user/queue/notifications", new QueueFrameHandler(notifFrames));
            devSession.subscribe(controlTowerDest, new QueueFrameHandler(controlTowerFrames));

            // Allow broker subscription registration to complete
            Thread.sleep(250);

            // 1. Send chat message mentioning dev -> triggers both CHAT_MESSAGE_CREATED and NOTIFICATION_CREATED
            chatService.sendMessage(
                    f.ownerPrincipal(),
                    f.workspaceId(),
                    f.projectId(),
                    teamChannel.getId(),
                    "Hello @dev live update",
                    List.of(f.devId()),
                    null
            );

            String rawChat = chatFrames.poll(5, TimeUnit.SECONDS);
            assertThat(rawChat).isNotNull();
            JsonNode chatEnvelope = jsonMapper.readTree(rawChat);
            assertThat(chatEnvelope.get("eventType").asText()).isEqualTo("CHAT_MESSAGE_CREATED");
            assertThat(chatEnvelope.get("workspaceId").asText()).isEqualTo(f.workspaceId().toString());
            assertThat(chatEnvelope.get("projectId").asText()).isEqualTo(f.projectId().toString());
            assertThat(chatEnvelope.get("payload").get("body").asText()).isEqualTo("Hello @dev live update");

            String rawNotif = notifFrames.poll(5, TimeUnit.SECONDS);
            assertThat(rawNotif).isNotNull();
            JsonNode notifEnvelope = jsonMapper.readTree(rawNotif);
            assertThat(notifEnvelope.get("eventType").asText()).isEqualTo("NOTIFICATION_CREATED");
            assertThat(notifEnvelope.get("payload").get("eventType").asText()).isEqualTo("CHAT_MENTION");

            // 2. Direct notification creation -> NOTIFICATION_CREATED
            notificationService.createNotification(
                    f.workspaceId(), f.devId(), f.projectId(),
                    "TASK_ASSIGNED", "task:assigned:1", "Assigned", "You have a new task", "/tasks/1");
            String rawDirectNotif = notifFrames.poll(5, TimeUnit.SECONDS);
            assertThat(rawDirectNotif).isNotNull();
            JsonNode directNotifEnvelope = jsonMapper.readTree(rawDirectNotif);
            assertThat(directNotifEnvelope.get("eventType").asText()).isEqualTo("NOTIFICATION_CREATED");
            assertThat(directNotifEnvelope.get("payload").get("title").asText()).isEqualTo("Assigned");

            // 3. Alert create + status update -> ALERT_UPSERTED & ALERT_UPDATED on control-tower
            Alert alert = alertService.createOrUpdateAlert(
                    f.projectId(), null, "SCHEDULE_RISK", Alert.Severity.HIGH,
                    "Schedule slip detected", Map.of("schemaVersion", 1), Map.of("schemaVersion", 1));

            String rawAlertCreated = controlTowerFrames.poll(5, TimeUnit.SECONDS);
            assertThat(rawAlertCreated).isNotNull();
            JsonNode alertUpsertEnvelope = jsonMapper.readTree(rawAlertCreated);
            assertThat(alertUpsertEnvelope.get("eventType").asText()).isEqualTo("ALERT_UPSERTED");
            assertThat(alertUpsertEnvelope.get("payload").get("id").asText()).isEqualTo(alert.getId().toString());

            alertService.updateAlertStatus(
                    f.ownerPrincipal(), f.workspaceId(), f.projectId(), alert.getId(),
                    new UpdateAlertStatusRequest(Alert.Status.ACKNOWLEDGED, null, alert.getVersion()));

            String rawAlertUpdated = controlTowerFrames.poll(5, TimeUnit.SECONDS);
            assertThat(rawAlertUpdated).isNotNull();
            JsonNode alertUpdatedEnvelope = jsonMapper.readTree(rawAlertUpdated);
            assertThat(alertUpdatedEnvelope.get("eventType").asText()).isEqualTo("ALERT_UPDATED");
            assertThat(alertUpdatedEnvelope.get("payload").get("status").asText()).isEqualTo("ACKNOWLEDGED");

            devSession.disconnect();
        } finally {
            client.stop();
        }
    }

    @Test
    @DisplayName("Enforces SUBSCRIBE RBAC: CLIENT-only denied internal chat & control-tower, allowed CLIENT_PM")
    void enforcesSubscribeRbacForClientUser() throws Exception {
        Fixture f = createFixture();
        ChatChannel teamChannel = chatService.createChannel(
                f.ownerPrincipal(), f.workspaceId(), f.projectId(), ChatChannel.Type.TEAM, "internal-only", null);
        ChatChannel clientPmChannel = chatService.createChannel(
                f.ownerPrincipal(), f.workspaceId(), f.projectId(), ChatChannel.Type.CLIENT_PM, "client-sync", null);
        chatService.addMember(f.ownerPrincipal(), f.workspaceId(), f.projectId(), clientPmChannel.getId(), f.clientId());

        // 1. CLIENT subscribing to internal TEAM channel must trigger STOMP ERROR / disconnect
        assertSubscribeDenied(
                f.clientToken(),
                "/topic/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/chat/" + teamChannel.getId());

        // 2. CLIENT subscribing to control-tower must trigger STOMP ERROR / disconnect
        assertSubscribeDenied(
                f.clientToken(),
                "/topic/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/control-tower");

        // 3. Unknown destination must trigger STOMP ERROR / disconnect
        assertSubscribeDenied(f.devToken(), "/topic/arbitrary/unknown");

        // 4. CLIENT subscribing to CLIENT_PM channel succeeds and receives messages
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        try {
            StompHeaders headers = new StompHeaders();
            headers.add("Authorization", "Bearer " + f.clientToken());
            StompSession session = client.connectAsync(
                    wsUrl(), new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS);

            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            session.subscribe(
                    "/topic/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/chat/" + clientPmChannel.getId(),
                    new QueueFrameHandler(frames));
            Thread.sleep(250);

            chatService.sendMessage(
                    f.ownerPrincipal(), f.workspaceId(), f.projectId(), clientPmChannel.getId(),
                    "Welcome client", List.of(), null);

            String raw = frames.poll(5, TimeUnit.SECONDS);
            assertThat(raw).isNotNull();
            assertThat(jsonMapper.readTree(raw).get("payload").get("body").asText()).isEqualTo("Welcome client");
            session.disconnect();
        } finally {
            client.stop();
        }
    }

    @Test
    @DisplayName("TC-03: Removing workspace member immediately closes active WebSocket session with POLICY_VIOLATION (1008)")
    void tc03RemovingWorkspaceMemberForcesPolicyViolationDisconnect() throws Exception {
        Fixture f = createFixture();
        ChatChannel teamChannel = chatService.createChannel(
                f.ownerPrincipal(), f.workspaceId(), f.projectId(), ChatChannel.Type.TEAM, "team-room", null);
        chatService.addMember(f.ownerPrincipal(), f.workspaceId(), f.projectId(), teamChannel.getId(), f.devId());

        CompletableFuture<CloseStatus> closeStatusFuture = new CompletableFuture<>();
        WebSocketClient spyClient = new CloseStatusCapturingWebSocketClient(new StandardWebSocketClient(), closeStatusFuture);
        WebSocketStompClient stompClient = new WebSocketStompClient(spyClient);
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());

        try {
            StompHeaders headers = new StompHeaders();
            headers.add("Authorization", "Bearer " + f.devToken());

            StompSession devSession = stompClient.connectAsync(
                    wsUrl(), new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS);

            BlockingQueue<String> frames = new LinkedBlockingQueue<>();
            String chatDest = "/topic/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/chat/" + teamChannel.getId();
            devSession.subscribe(chatDest, new QueueFrameHandler(frames));
            Thread.sleep(250);

            // Owner removes dev from workspace -> afterCommit must close dev's WS with 1008 POLICY_VIOLATION
            workspaceMembershipService.removeMember(f.ownerPrincipal(), f.workspaceId(), f.devId());

            CloseStatus receivedCloseStatus = closeStatusFuture.get(5, TimeUnit.SECONDS);
            assertThat(receivedCloseStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
            assertThat(devSession.isConnected()).isFalse();

            // Subsequent attempt to re-subscribe to the same chat topic must be denied by live RBAC
            assertSubscribeDenied(f.devToken(), chatDest);
        } finally {
            stompClient.stop();
        }
    }

    private void assertSubscribeDenied(String bearerToken, String destination) throws Exception {
        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        CompletableFuture<CloseStatus> closeFuture = new CompletableFuture<>();

        WebSocketClient spyClient = new CloseStatusCapturingWebSocketClient(new StandardWebSocketClient(), closeFuture);
        WebSocketStompClient client = new WebSocketStompClient(spyClient);
        client.setMessageConverter(new JacksonJsonMessageConverter());

        try {
            StompHeaders headers = new StompHeaders();
            headers.add("Authorization", "Bearer " + bearerToken);

            StompSession session = client.connectAsync(
                    wsUrl(),
                    new WebSocketHttpHeaders(),
                    headers,
                    new StompSessionHandlerAdapter() {
                        @Override
                        public void handleException(
                                StompSession s, StompCommand command, StompHeaders h, byte[] payload, Throwable exception) {
                            errorFuture.complete(exception);
                        }

                        @Override
                        public void handleTransportError(StompSession s, Throwable exception) {
                            errorFuture.complete(exception);
                        }
                    }
            ).get(5, TimeUnit.SECONDS);

            session.subscribe(destination, new QueueFrameHandler(new LinkedBlockingQueue<>()));

            // Spring STOMP inbound AccessDeniedException sends an ERROR frame and closes the connection
            Object outcome = CompletableFuture.anyOf(errorFuture, closeFuture).get(5, TimeUnit.SECONDS);
            assertThat(outcome).isNotNull();
        } finally {
            client.stop();
        }
    }

    private static final class QueueFrameHandler implements StompFrameHandler {
        private final BlockingQueue<String> queue;

        private QueueFrameHandler(BlockingQueue<String> queue) {
            this.queue = queue;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return JsonNode.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            if (payload instanceof JsonNode node) {
                queue.offer(node.toString());
            } else if (payload instanceof byte[] bytes) {
                queue.offer(new String(bytes, StandardCharsets.UTF_8));
            } else if (payload != null) {
                queue.offer(payload.toString());
            }
        }
    }

    private static final class CloseStatusCapturingWebSocketClient implements WebSocketClient {
        private final WebSocketClient delegate;
        private final CompletableFuture<CloseStatus> closeStatusFuture;

        private CloseStatusCapturingWebSocketClient(
                WebSocketClient delegate,
                CompletableFuture<CloseStatus> closeStatusFuture) {
            this.delegate = delegate;
            this.closeStatusFuture = closeStatusFuture;
        }

        @Override
        public CompletableFuture<WebSocketSession> execute(
                WebSocketHandler webSocketHandler, String uriTemplate, Object... uriVariables) {
            return delegate.execute(wrap(webSocketHandler), uriTemplate, uriVariables);
        }

        @Override
        public CompletableFuture<WebSocketSession> execute(
                WebSocketHandler webSocketHandler, WebSocketHttpHeaders headers, URI uri) {
            return delegate.execute(wrap(webSocketHandler), headers, uri);
        }

        private WebSocketHandler wrap(WebSocketHandler handler) {
            return new WebSocketHandlerDecorator(handler) {
                @Override
                public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                    closeStatusFuture.complete(closeStatus);
                    super.afterConnectionClosed(session, closeStatus);
                }
            };
        }
    }
}
