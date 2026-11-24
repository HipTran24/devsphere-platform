package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.ai.application.AiTaskRouter;
import com.project.devsphere.ai.domain.AiJob;
import com.project.devsphere.ai.domain.AiProviderType;
import com.project.devsphere.communication.application.ChatService;
import com.project.devsphere.gitintegration.infrastructure.GitHubInstallationTokenService;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.monitoring.application.AlertService;
import com.project.devsphere.monitoring.domain.Alert;
import com.project.devsphere.operations.application.DemoSeedDataService;
import com.project.devsphere.operations.application.DemoSeedRunner;
import com.project.devsphere.workspace.application.WorkspaceMembershipService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "devsphere.database.manage-runtime-privileges=false",
                "devsphere.auth.mail.from=devsphere@example.test",
                "spring.mail.host=localhost",
                "spring.mail.port=2525",
                "devsphere.auth.mail.initial-delay-ms=600000",
                "spring.quartz.auto-startup=false",
                "devsphere.quartz.auto-startup=false",
                "devsphere.seed.demo.enabled=true",
                "devsphere.ai.allow-loopback-self-host=true",
                "devsphere.github.webhook-secret=test-week8-e2e-webhook-key"
        }
)
class Week8EndToEndJourneyIntegrationTest {

    private static final String WEBHOOK_SECRET = "test-week8-e2e-webhook-key";
    private static final String SHA_SYNC_PR = "1111111111111111111111111111111111111111";
    private static final String SHA_SYNC_COMMIT = "2222222222222222222222222222222222222222";
    private static final String SHA_WEBHOOK_PR = "3333333333333333333333333333333333333333";

    private static HttpServer aiMockServer;
    private static int aiMockPort;
    private static final AtomicReference<MockResponder> AI_RESPONDER = new AtomicReference<>();

    private static HttpServer cloudSpyServer;
    private static int cloudSpyPort;
    private static final AtomicInteger CLOUD_SPY_HITS = new AtomicInteger(0);

    private static KeyPair githubRsaKeyPair;
    private static String githubPrivateKeyPemEscaped;
    private static HttpServer githubMockServer;
    private static int githubMockPort;
    private static final AtomicBoolean GITHUB_JWT_VERIFIED = new AtomicBoolean(false);
    private static final AtomicInteger GITHUB_TOKEN_EXCHANGES = new AtomicInteger(0);
    private static final AtomicReference<String> GITHUB_PRS_JSON = new AtomicReference<>("[]");
    private static final AtomicReference<String> GITHUB_COMMITS_JSON = new AtomicReference<>("[]");
    private static final AtomicReference<String> GITHUB_CHECKS_JSON = new AtomicReference<>("{\"total_count\":0,\"check_runs\":[]}");
    private static final AtomicReference<String> GITHUB_REVIEWS_JSON = new AtomicReference<>("[]");

    private static HttpServer s3MockServer;
    private static int s3MockPort;
    private static final Map<String, byte[]> S3_STORE = new ConcurrentHashMap<>();
    private static final AtomicInteger S3_HEAD_HITS = new AtomicInteger(0);
    private static final AtomicInteger S3_PUT_HITS = new AtomicInteger(0);
    private static String previousAccessKey;
    private static String previousSecretKey;

    @LocalServerPort
    private int port;

    @Autowired private ApplicationContext applicationContext;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessionService;
    @Autowired private ChatService chatService;
    @Autowired private AlertService alertService;
    @Autowired private WorkspaceMembershipService workspaceMembershipService;
    @Autowired private AiTaskRouter aiTaskRouter;
    @Autowired private GitHubInstallationTokenService installationTokenService;
    @MockitoBean private JavaMailSender mailSender;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @FunctionalInterface
    interface MockResponder {
        MockReply respond(String requestBody) throws Exception;
    }

    record MockReply(int statusCode, String bodyJson) {
    }

    @BeforeAll
    static void startEmbeddedServers() throws Exception {
        previousAccessKey = System.getProperty("aws.accessKeyId");
        previousSecretKey = System.getProperty("aws.secretAccessKey");
        System.setProperty("aws.accessKeyId", "AKIAIOSFODNN7EXAMPLE");
        System.setProperty("aws.secretAccessKey", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");

        // 1. Embedded vLLM / OpenAI-compatible AI mock server
        aiMockServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        aiMockPort = aiMockServer.getAddress().getPort();
        aiMockServer.createContext("/v1/chat/completions", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                MockReply reply = AI_RESPONDER.get().respond(reqBody);
                writeJson(exchange, reply.statusCode(), reply.bodyJson());
            } catch (Exception ex) {
                writeJson(exchange, 500, "{\"error\":\"mock responder failed\"}");
            }
        });
        aiMockServer.start();

        // 2. Cloud AI spy server (must receive 0 requests under SELF_HOST_ONLY)
        cloudSpyServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        cloudSpyPort = cloudSpyServer.getAddress().getPort();
        cloudSpyServer.createContext("/v1/chat/completions", exchange -> {
            CLOUD_SPY_HITS.incrementAndGet();
            writeJson(exchange, 200, wrapChatCompletion("{\"reply\":\"cloud\"}", 10, 5));
        });
        cloudSpyServer.start();

        // 3. Embedded GitHub App API mock server with ephemeral 2048-bit RSA keypair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        githubRsaKeyPair = keyGen.generateKeyPair();
        String encodedPrivate = Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(githubRsaKeyPair.getPrivate().getEncoded());
        githubPrivateKeyPemEscaped = ("-----BEGIN PRIVATE KEY-----\n" + encodedPrivate + "\n-----END PRIVATE KEY-----")
                .replace("\n", "\\n");

        githubMockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        githubMockPort = githubMockServer.getAddress().getPort();
        githubMockServer.createContext("/app/installations/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String jwt = auth.substring("Bearer ".length());
                String[] parts = jwt.split("\\.");
                if (parts.length == 3) {
                    try {
                        Signature verifier = Signature.getInstance("SHA256withRSA");
                        verifier.initVerify(githubRsaKeyPair.getPublic());
                        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                        if (verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                            GITHUB_JWT_VERIFIED.set(true);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            int seq = GITHUB_TOKEN_EXCHANGES.incrementAndGet();
            writeJson(exchange, 201, "{\"token\":\"ghs_e2e_token_" + seq + "\",\"expires_at\":\"2099-01-01T00:00:00Z\"}");
        });
        githubMockServer.createContext("/repos/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/pulls")) {
                writeJson(exchange, 200, GITHUB_PRS_JSON.get());
            } else if (path.endsWith("/commits")) {
                writeJson(exchange, 200, GITHUB_COMMITS_JSON.get());
            } else if (path.endsWith("/check-runs")) {
                writeJson(exchange, 200, GITHUB_CHECKS_JSON.get());
            } else if (path.endsWith("/reviews")) {
                writeJson(exchange, 200, GITHUB_REVIEWS_JSON.get());
            } else {
                writeJson(exchange, 404, "{\"message\":\"not found\"}");
            }
        });
        githubMockServer.start();

        // 4. Embedded S3 mock server supporting PUT, HEAD, and GET
        s3MockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s3MockPort = s3MockServer.getAddress().getPort();
        s3MockServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("PUT".equalsIgnoreCase(method)) {
                S3_PUT_HITS.incrementAndGet();
                byte[] body = exchange.getRequestBody().readAllBytes();
                S3_STORE.put(path, body);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            if ("HEAD".equalsIgnoreCase(method)) {
                S3_HEAD_HITS.incrementAndGet();
                byte[] existing = S3_STORE.get(path);
                if (existing != null) {
                    exchange.getResponseHeaders().add("Content-Length", String.valueOf(existing.length));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
                exchange.close();
                return;
            }
            if ("GET".equalsIgnoreCase(method)) {
                byte[] existing = S3_STORE.get(path);
                if (existing != null) {
                    exchange.sendResponseHeaders(200, existing.length);
                    exchange.getResponseBody().write(existing);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        });
        s3MockServer.start();
    }

    @AfterAll
    static void stopEmbeddedServers() {
        if (aiMockServer != null) {
            aiMockServer.stop(0);
        }
        if (cloudSpyServer != null) {
            cloudSpyServer.stop(0);
        }
        if (githubMockServer != null) {
            githubMockServer.stop(0);
        }
        if (s3MockServer != null) {
            s3MockServer.stop(0);
        }
        if (previousAccessKey == null) {
            System.clearProperty("aws.accessKeyId");
        } else {
            System.setProperty("aws.accessKeyId", previousAccessKey);
        }
        if (previousSecretKey == null) {
            System.clearProperty("aws.secretAccessKey");
        } else {
            System.setProperty("aws.secretAccessKey", previousSecretKey);
        }
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] aiKey = new byte[32];
        byte[] hmac = new byte[32];
        byte[] otpEnc = new byte[32];
        new SecureRandom().nextBytes(aiKey);
        hmac[0] = 51;
        otpEnc[0] = 52;
        registry.add("devsphere.auth.otp.hmac-key", () -> Base64.getEncoder().encodeToString(hmac));
        registry.add("devsphere.auth.otp.encryption-key", () -> Base64.getEncoder().encodeToString(otpEnc));
        registry.add("devsphere.ai.encryption-key", () -> Base64.getEncoder().encodeToString(aiKey));

        registry.add("devsphere.github.app.enabled", () -> "true");
        registry.add("devsphere.github.app.app-id", () -> "99008");
        registry.add("devsphere.github.app.private-key-pem", () -> githubPrivateKeyPemEscaped);
        registry.add("devsphere.github.app.api-base-url", () -> "http://127.0.0.1:" + githubMockPort);
        registry.add("devsphere.github.app.allow-loopback-api", () -> "true");

        registry.add("devsphere.storage.s3.mode", () -> "aws");
        registry.add("devsphere.storage.s3.endpoint", () -> "http://127.0.0.1:" + s3MockPort);
        registry.add("devsphere.storage.s3.path-style-access", () -> "true");
        registry.add("devsphere.storage.s3.allow-insecure-endpoint", () -> "true");
        registry.add("devsphere.storage.s3.verify-on-confirm", () -> "true");
        registry.add("devsphere.storage.s3.bucket", () -> "devsphere-attachments");
        registry.add("devsphere.storage.s3.region", () -> "us-east-1");
    }

    @Test
    @DisplayName("Full Week 8 cross-module backend journey: Seed + Auth + STOMP Realtime + AI Router/Gate + GitHub App/Webhooks + S3 Report Exports")
    void fullWeek8CrossModuleBackendJourney() throws Exception {
        S3_STORE.clear();
        S3_HEAD_HITS.set(0);
        S3_PUT_HITS.set(0);
        CLOUD_SPY_HITS.set(0);
        GITHUB_JWT_VERIFIED.set(false);
        GITHUB_TOKEN_EXCHANGES.set(0);
        installationTokenService.invalidateToken(DemoSeedDataService.DEMO_GITHUB_INSTALLATION_ID);

        // =========================================================================
        // PHASE 1: DemoSeedRunner startup + CSRF/Bearer Auth + Idempotent /seed-demo
        // =========================================================================
        assertThat(applicationContext.getBeansOfType(DemoSeedRunner.class)).hasSize(1);

        // Fetch CSRF token & cookie, verify login without CSRF fails with 403, then login as seeded Admin
        HttpResponse<String> csrfRes = httpClient.send(
                HttpRequest.newBuilder(URI.create(httpUrl("/api/v1/auth/csrf"))).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(csrfRes.statusCode()).isEqualTo(200);
        JsonNode csrfBody = json.readTree(csrfRes.body());
        String csrfHeader = csrfBody.get("headerName").asText();
        String csrfToken = csrfBody.get("token").asText();
        String csrfCookiePair = extractCookiePair(csrfRes.headers().allValues("Set-Cookie"), "__Host-devsphere-csrf");
        assertThat(csrfCookiePair).isNotNull();

        String adminLoginPayload = json.writeValueAsString(Map.of(
                "email", DemoSeedDataService.ADMIN_EMAIL,
                "password", DemoSeedDataService.DEFAULT_DEMO_PASSPHRASE
        ));
        HttpResponse<String> loginNoCsrf = httpClient.send(
                HttpRequest.newBuilder(URI.create(httpUrl("/api/v1/auth/login")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(adminLoginPayload, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(loginNoCsrf.statusCode()).isEqualTo(403);

        HttpResponse<String> adminLoginRes = httpClient.send(
                HttpRequest.newBuilder(URI.create(httpUrl("/api/v1/auth/login")))
                        .header("Content-Type", "application/json")
                        .header("Cookie", csrfCookiePair)
                        .header(csrfHeader, csrfToken)
                        .POST(HttpRequest.BodyPublishers.ofString(adminLoginPayload, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(adminLoginRes.statusCode()).isEqualTo(200);
        String adminToken = json.readTree(adminLoginRes.body()).get("accessToken").asText();

        HttpResponse<String> meRes = sendJson("GET", "/api/v1/auth/me", adminToken, null);
        assertThat(meRes.statusCode()).isEqualTo(200);
        assertThat(json.readTree(meRes.body()).get("userId").asText())
                .isEqualTo(DemoSeedDataService.ADMIN_USER_ID.toString());

        // Trigger POST /api/v1/admin/operations/seed-demo over live HTTP (idempotent re-seed)
        HttpResponse<String> seedEndpointRes = sendJson("POST", "/api/v1/admin/operations/seed-demo", adminToken, null);
        assertThat(seedEndpointRes.statusCode()).isEqualTo(200);
        JsonNode seedJson = json.readTree(seedEndpointRes.body());
        assertThat(seedJson.get("seeded").asBoolean()).isTrue();
        assertThat(seedJson.get("workspaceSlug").asText()).isEqualTo(DemoSeedDataService.WORKSPACE_SLUG);
        assertThat(seedJson.get("projectKey").asText()).isEqualTo(DemoSeedDataService.PROJECT_KEY);
        assertThat(seedJson.get("counts").get("users").asInt()).isEqualTo(7);

        UUID workspaceId = DemoSeedDataService.WORKSPACE_ID;
        UUID projectId = DemoSeedDataService.PROJECT_ID;
        UUID teamChannelId = DemoSeedDataService.TEAM_CHANNEL_ID;
        UUID clientPmChannelId = DemoSeedDataService.CLIENT_PM_CHANNEL_ID;

        // Obtain tokens & principals for all seeded personas via Argon2id login
        String ownerToken = sessionService.login(DemoSeedDataService.OWNER_EMAIL, DemoSeedDataService.DEFAULT_DEMO_PASSPHRASE).access().value();
        String pmToken = sessionService.login(DemoSeedDataService.PM_EMAIL, DemoSeedDataService.DEFAULT_DEMO_PASSPHRASE).access().value();
        String dev1Token = sessionService.login(DemoSeedDataService.DEV1_EMAIL, DemoSeedDataService.DEFAULT_DEMO_PASSPHRASE).access().value();
        String clientToken = sessionService.login(DemoSeedDataService.CLIENT_EMAIL, DemoSeedDataService.DEFAULT_DEMO_PASSPHRASE).access().value();
        String viewerToken = sessionService.login(DemoSeedDataService.VIEWER_EMAIL, DemoSeedDataService.DEFAULT_DEMO_PASSPHRASE).access().value();

        SessionService.Principal ownerPrincipal = sessionService.authenticate(ownerToken).orElseThrow();
        SessionService.Principal pmPrincipal = sessionService.authenticate(pmToken).orElseThrow();

        // =========================================================================
        // PHASE 2: Live STOMP Chat, Notification Push, CLIENT Isolation & TC-03 Disconnect
        // =========================================================================
        assertSubscribeDenied(clientToken, "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/chat/" + teamChannelId);
        assertSubscribeDenied(clientToken, "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/control-tower");

        WebSocketStompClient devStompClient = new WebSocketStompClient(new StandardWebSocketClient());
        devStompClient.setMessageConverter(new JacksonJsonMessageConverter());

        CompletableFuture<CloseStatus> viewerCloseFuture = new CompletableFuture<>();
        WebSocketStompClient viewerStompClient = new WebSocketStompClient(
                new CloseStatusCapturingWebSocketClient(new StandardWebSocketClient(), viewerCloseFuture)
        );
        viewerStompClient.setMessageConverter(new JacksonJsonMessageConverter());

        WebSocketStompClient clientStompClient = new WebSocketStompClient(new StandardWebSocketClient());
        clientStompClient.setMessageConverter(new JacksonJsonMessageConverter());

        BlockingQueue<String> devChatFrames = new LinkedBlockingQueue<>();
        BlockingQueue<String> devNotifFrames = new LinkedBlockingQueue<>();
        BlockingQueue<String> devAlertFrames = new LinkedBlockingQueue<>();
        BlockingQueue<String> clientChatFrames = new LinkedBlockingQueue<>();

        try {
            StompSession devSession = connectStomp(devStompClient, dev1Token);
            String teamChatDest = "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/chat/" + teamChannelId;
            String controlTowerDest = "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/control-tower";
            devSession.subscribe(teamChatDest, new QueueFrameHandler(devChatFrames));
            devSession.subscribe("/user/queue/notifications", new QueueFrameHandler(devNotifFrames));
            devSession.subscribe(controlTowerDest, new QueueFrameHandler(devAlertFrames));

            StompSession clientSession = connectStomp(clientStompClient, clientToken);
            String clientPmDest = "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/chat/" + clientPmChannelId;
            clientSession.subscribe(clientPmDest, new QueueFrameHandler(clientChatFrames));

            StompSession viewerSession = connectStomp(viewerStompClient, viewerToken);
            viewerSession.subscribe(teamChatDest, new QueueFrameHandler(new LinkedBlockingQueue<>()));

            Thread.sleep(250);

            // Live TEAM chat broadcast + mention notification to dev1
            chatService.sendMessage(
                    pmPrincipal, workspaceId, projectId, teamChannelId,
                    "Hello @dev1 live sprint sync", List.of(DemoSeedDataService.DEV1_USER_ID), null
            );
            String teamFrame = devChatFrames.poll(5, TimeUnit.SECONDS);
            assertThat(teamFrame).isNotNull();
            assertThat(json.readTree(teamFrame).get("eventType").asText()).isEqualTo("CHAT_MESSAGE_CREATED");
            assertThat(json.readTree(teamFrame).get("payload").get("body").asText()).isEqualTo("Hello @dev1 live sprint sync");

            String notifFrame = devNotifFrames.poll(5, TimeUnit.SECONDS);
            assertThat(notifFrame).isNotNull();
            assertThat(json.readTree(notifFrame).get("eventType").asText()).isEqualTo("NOTIFICATION_CREATED");
            assertThat(json.readTree(notifFrame).get("payload").get("eventType").asText()).isEqualTo("CHAT_MENTION");

            // CLIENT receives CLIENT_PM channel broadcast
            chatService.sendMessage(
                    pmPrincipal, workspaceId, projectId, clientPmChannelId,
                    "Client roadmap sync ready", List.of(), null
            );
            String clientFrame = clientChatFrames.poll(5, TimeUnit.SECONDS);
            assertThat(clientFrame).isNotNull();
            assertThat(json.readTree(clientFrame).get("payload").get("body").asText()).isEqualTo("Client roadmap sync ready");

            // Control Tower alert broadcast
            alertService.createOrUpdateAlert(
                    projectId, DemoSeedDataService.TASK_6_PROGRESS_ID, "SCHEDULE_RISK", Alert.Severity.HIGH,
                    "E2E schedule alert", Map.of("schemaVersion", 1), Map.of("schemaVersion", 1)
            );
            String alertFrame = devAlertFrames.poll(5, TimeUnit.SECONDS);
            assertThat(alertFrame).isNotNull();
            assertThat(json.readTree(alertFrame).get("eventType").asText()).isEqualTo("ALERT_UPSERTED");

            // TC-03: Removing viewer from workspace closes active WS session immediately with POLICY_VIOLATION (1008)
            workspaceMembershipService.removeMember(ownerPrincipal, workspaceId, DemoSeedDataService.VIEWER_USER_ID);
            CloseStatus closeStatus = viewerCloseFuture.get(5, TimeUnit.SECONDS);
            assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
            assertThat(viewerSession.isConnected()).isFalse();

            // =========================================================================
            // PHASE 3: AI Config, TC-21, TC-22 (SELF_HOST_ONLY), TC-23, TC-24, TC-25, @ProjectAI
            // =========================================================================
            HttpResponse<String> putConns = sendJson(
                    "PUT",
                    "/api/v1/workspaces/" + workspaceId + "/ai/config/connections",
                    ownerToken,
                    Map.of("connections", List.of(
                            Map.of(
                                    "id", "openai-cloud",
                                    "provider", "OPENAI",
                                    "baseUrl", "https://api.openai.com",
                                    "model", "gpt-4o",
                                    "enabled", true,
                                    "apiKey", "test-mock-e2e-cloud-key"
                            ),
                            Map.of(
                                    "id", "vllm-local",
                                    "provider", "VLLM_SELF_HOST",
                                    "baseUrl", "http://127.0.0.1:" + aiMockPort,
                                    "model", "qwen-2.5-coder",
                                    "enabled", true,
                                    "apiKey", "vllm-e2e-token"
                            )
                    ))
            );
            assertThat(putConns.statusCode()).isEqualTo(200);

            HttpResponse<String> putRoutes = sendJson(
                    "PUT",
                    "/api/v1/workspaces/" + workspaceId + "/ai/config/routes",
                    ownerToken,
                    Map.of("routes", Map.of(
                            "REQUIREMENT_PLAN", "vllm-local",
                            "PR_REVIEW", "vllm-local",
                            "REPORT_SUMMARY", "vllm-local",
                            "CHAT_ANSWER", "openai-cloud"
                    ))
            );
            assertThat(putRoutes.statusCode()).isEqualTo(200);

            // TC-21: CLOUD_ALLOWED routes CHAT_ANSWER to OPENAI and executes REQUIREMENT_PLAN on VLLM_SELF_HOST
            assertThat(aiTaskRouter.resolve(workspaceId, AiJob.JobType.CHAT_ANSWER).provider())
                    .isEqualTo(AiProviderType.OPENAI);

            AI_RESPONDER.set(req -> new MockReply(
                    200, wrapChatCompletion("{\"items\":[{\"tempKey\":\"T1\",\"estimateMinutes\":120}]}", 80, 30)
            ));
            UUID validPlanJob = createAiJob(workspaceId, projectId, dev1Token, "REQUIREMENT_PLAN", Map.of("schemaVersion", 1));
            HttpResponse<String> validPlanRes = sendJson(
                    "POST",
                    "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ai/jobs/" + validPlanJob + "/execute",
                    dev1Token, null
            );
            assertThat(validPlanRes.statusCode()).isEqualTo(200);
            assertThat(json.readTree(validPlanRes.body()).get("validationStatus").asText()).isEqualTo("VALID");

            // TC-22: SELF_HOST_ONLY blocks OPENAI before any HTTP call (0 cloud hits, 503 AI_UNAVAILABLE)
            HttpResponse<String> putPrivacy = sendJson(
                    "PUT",
                    "/api/v1/workspaces/" + workspaceId + "/ai/config/privacy",
                    ownerToken,
                    Map.of("privacyPolicy", "SELF_HOST_ONLY")
            );
            assertThat(putPrivacy.statusCode()).isEqualTo(200);

            // Point openai-cloud to local spy in DB to prove 0 outbound HTTP requests occur
            String spyConnectionsJson = json.writeValueAsString(List.of(
                    Map.of("id", "openai-cloud", "provider", "OPENAI", "baseUrl", "http://127.0.0.1:" + cloudSpyPort, "model", "gpt-4o", "enabled", true),
                    Map.of("id", "vllm-local", "provider", "VLLM_SELF_HOST", "baseUrl", "http://127.0.0.1:" + aiMockPort, "model", "qwen-2.5-coder", "enabled", true)
            ));
            jdbc.update("UPDATE workspaces SET ai_connections = ?::jsonb WHERE id = ?", spyConnectionsJson, workspaceId);

            UUID blockedCloudJob = createAiJob(workspaceId, projectId, dev1Token, "CHAT_ANSWER", Map.of("schemaVersion", 1, "prompt", "hello"));
            HttpResponse<String> blockedCloudRes = sendJson(
                    "POST",
                    "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ai/jobs/" + blockedCloudJob + "/execute",
                    dev1Token, null
            );
            assertThat(blockedCloudRes.statusCode()).isEqualTo(503);
            assertThat(json.readTree(blockedCloudRes.body()).get("code").asText()).isEqualTo("AI_UNAVAILABLE");
            assertThat(CLOUD_SPY_HITS.get()).isZero();

            // TC-23: Negative estimate AND dependency cycle rejected by AiValidationGate
            AI_RESPONDER.set(req -> new MockReply(
                    200, wrapChatCompletion("{\"items\":[{\"tempKey\":\"T1\",\"estimateMinutes\":-15}]}", 40, 10)
            ));
            UUID negEstJob = createAiJob(workspaceId, projectId, dev1Token, "REQUIREMENT_PLAN", Map.of("schemaVersion", 1));
            JsonNode negEstNode = json.readTree(sendJson(
                    "POST",
                    "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ai/jobs/" + negEstJob + "/execute",
                    dev1Token, null
            ).body());
            assertThat(negEstNode.get("validationStatus").asText()).isEqualTo("INVALID");

            AI_RESPONDER.set(req -> new MockReply(
                    200, wrapChatCompletion(
                            "{\"items\":[{\"tempKey\":\"A\",\"estimateMinutes\":30},{\"tempKey\":\"B\",\"estimateMinutes\":30}],"
                                    + "\"dependencies\":[{\"predecessorKey\":\"A\",\"successorKey\":\"B\"},{\"predecessorKey\":\"B\",\"successorKey\":\"A\"}]}",
                            40, 10)
            ));
            UUID cycleJob = createAiJob(workspaceId, projectId, dev1Token, "REQUIREMENT_PLAN", Map.of("schemaVersion", 1));
            JsonNode cycleNode = json.readTree(sendJson(
                    "POST",
                    "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ai/jobs/" + cycleJob + "/execute",
                    dev1Token, null
            ).body());
            assertThat(cycleNode.get("validationStatus").asText()).isEqualTo("INVALID");
            assertThat(cycleNode.get("validationReport").get("dependencyCycleDetected").asBoolean()).isTrue();

            // TC-24: PR_REVIEW out-of-bounds diff line rejected
            AI_RESPONDER.set(req -> new MockReply(
                    200, wrapChatCompletion(
                            "{\"status\":\"REQUIRES_CHANGES\",\"findings\":[{\"filePath\":\"src/Main.java\",\"lineNumber\":500,\"message\":\"Out of diff\"}]}",
                            35, 15)
            ));
            UUID badPrReviewJob = createAiJob(workspaceId, projectId, dev1Token, "PR_REVIEW", Map.of(
                    "schemaVersion", 1,
                    "diffFiles", Map.of("src/Main.java", 40)
            ));
            JsonNode badPrReviewNode = json.readTree(sendJson(
                    "POST",
                    "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ai/jobs/" + badPrReviewJob + "/execute",
                    dev1Token, null
            ).body());
            assertThat(badPrReviewNode.get("validationStatus").asText()).isEqualTo("INVALID");
            assertThat(badPrReviewNode.get("validationReport").get("invalidFindings")).hasSize(1);

            // TC-25: REPORT_SUMMARY fact mismatch -> NEEDS_REVIEW; accurate DB counts -> VALID
            int totalWorkItems = jdbc.queryForObject("SELECT count(*) FROM work_items WHERE project_id = ? AND deleted_at IS NULL", Integer.class, projectId);
            int completedWorkItems = jdbc.queryForObject("SELECT count(*) FROM work_items WHERE project_id = ? AND deleted_at IS NULL AND status = 'DONE'", Integer.class, projectId);
            int openAlerts = jdbc.queryForObject("SELECT count(*) FROM alerts WHERE project_id = ? AND status <> 'RESOLVED'", Integer.class, projectId);
            int activeSprints = jdbc.queryForObject("SELECT count(*) FROM sprints WHERE project_id = ? AND status = 'ACTIVE'", Integer.class, projectId);

            AI_RESPONDER.set(req -> new MockReply(
                    200, wrapChatCompletion(
                            "{\"summary\":\"Hallucinated metrics\",\"metrics\":{\"totalWorkItems\":" + (totalWorkItems + 50)
                                    + ",\"completedWorkItems\":" + (completedWorkItems + 10)
                                    + ",\"openAlerts\":0,\"activeSprints\":" + activeSprints + "}}",
                            50, 20)
            ));
            UUID mismatchReportJob = createAiJob(workspaceId, projectId, dev1Token, "REPORT_SUMMARY", Map.of("schemaVersion", 1));
            JsonNode mismatchNode = json.readTree(sendJson(
                    "POST",
                    "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ai/jobs/" + mismatchReportJob + "/execute",
                    dev1Token, null
            ).body());
            assertThat(mismatchNode.get("validationStatus").asText()).isEqualTo("NEEDS_REVIEW");
            assertThat(mismatchNode.get("validationReport").get("factMismatches").size()).isGreaterThanOrEqualTo(2);

            AI_RESPONDER.set(req -> new MockReply(
                    200, wrapChatCompletion(
                            "{\"summary\":\"Verified metrics\",\"metrics\":{\"totalWorkItems\":" + totalWorkItems
                                    + ",\"completedWorkItems\":" + completedWorkItems
                                    + ",\"openAlerts\":" + openAlerts
                                    + ",\"activeSprints\":" + activeSprints + "}}",
                            50, 20)
            ));
            UUID accurateReportJob = createAiJob(workspaceId, projectId, dev1Token, "REPORT_SUMMARY", Map.of("schemaVersion", 1));
            JsonNode accurateNode = json.readTree(sendJson(
                    "POST",
                    "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ai/jobs/" + accurateReportJob + "/execute",
                    dev1Token, null
            ).body());
            assertThat(accurateNode.get("validationStatus").asText()).isEqualTo("VALID");

            // @ProjectAI chat reply routed to vllm-local under SELF_HOST_ONLY + live STOMP delivery
            assertThat(sendJson(
                    "PUT",
                    "/api/v1/workspaces/" + workspaceId + "/ai/config/routes",
                    ownerToken,
                    Map.of("routes", Map.of("CHAT_ANSWER", "vllm-local"))
            ).statusCode()).isEqualTo(200);

            AI_RESPONDER.set(req -> new MockReply(
                    200, wrapChatCompletion("{\"reply\":\"All sprint milestones are on track.\"}", 30, 12)
            ));
            HttpResponse<String> mentionRes = sendJson(
                    "POST",
                    "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/chat/channels/" + teamChannelId + "/messages",
                    dev1Token,
                    Map.of("body", "@ProjectAI summarize our sprint health")
            );
            assertThat(mentionRes.statusCode()).isEqualTo(201);
            UUID userPromptMsgId = UUID.fromString(json.readTree(mentionRes.body()).get("id").asText());

            Map<String, Object> aiReplyRow = jdbc.queryForMap(
                    "SELECT sender_kind, sender_id, ai_job_id, reply_to_id, body FROM chat_messages WHERE reply_to_id = ?",
                    userPromptMsgId
            );
            assertThat(aiReplyRow.get("sender_kind")).isEqualTo("AI");
            assertThat(aiReplyRow.get("sender_id")).isNull();
            assertThat(aiReplyRow.get("ai_job_id")).isNotNull();
            assertThat(aiReplyRow.get("body")).isEqualTo("All sprint milestones are on track.");

            String f1 = devChatFrames.poll(5, TimeUnit.SECONDS);
            String f2 = devChatFrames.poll(5, TimeUnit.SECONDS);
            assertThat(f1 + "\n" + f2)
                    .contains("@ProjectAI summarize our sprint health")
                    .contains("All sprint milestones are on track.");

            devSession.disconnect();
            clientSession.disconnect();
        } finally {
            devStompClient.stop();
            viewerStompClient.stop();
            clientStompClient.stop();
        }

        // =========================================================================
        // PHASE 4: GitHub App RS256 JWT + Installation Token + PM-only Sync + Webhooks + Part H
        // =========================================================================
        GITHUB_PRS_JSON.set(json.writeValueAsString(List.of(Map.of(
                "number", 105,
                "state", "open",
                "title", "DSP-5 Live GitHub App sync",
                "body", "Refs DSP-5",
                "updated_at", "2026-11-24T12:00:00Z",
                "user", Map.of("login", DemoSeedDataService.DEV1_GITHUB_LOGIN),
                "head", Map.of("sha", SHA_SYNC_PR, "ref", "feat/DSP-5")
        ))));
        GITHUB_COMMITS_JSON.set(json.writeValueAsString(List.of(Map.of(
                "sha", SHA_SYNC_COMMIT,
                "author", Map.of("login", DemoSeedDataService.DEV1_GITHUB_LOGIN),
                "commit", Map.of(
                        "message", "DSP-5 add live sync",
                        "author", Map.of("name", "Lead Developer", "date", "2026-11-24T11:55:00Z")
                )
        ))));
        GITHUB_CHECKS_JSON.set(json.writeValueAsString(Map.of(
                "total_count", 1,
                "check_runs", List.of(Map.of(
                        "id", 95001L,
                        "name", "e2e-verify",
                        "head_sha", SHA_SYNC_PR,
                        "status", "completed",
                        "conclusion", "success",
                        "started_at", "2026-11-24T12:01:00Z",
                        "completed_at", "2026-11-24T12:03:00Z",
                        "pull_requests", List.of(Map.of("number", 105))
                ))
        )));
        GITHUB_REVIEWS_JSON.set(json.writeValueAsString(List.of(
                Map.of("id", 75001L, "state", "APPROVED", "commit_id", SHA_SYNC_PR, "body", "Independent LGTM",
                        "submitted_at", "2026-11-24T12:04:00Z", "user", Map.of("login", DemoSeedDataService.DEV2_GITHUB_LOGIN)),
                Map.of("id", 75002L, "state", "APPROVED", "commit_id", SHA_SYNC_PR, "body", "Self approve blocked",
                        "submitted_at", "2026-11-24T12:05:00Z", "user", Map.of("login", DemoSeedDataService.DEV1_GITHUB_LOGIN))
        )));

        String syncPath = "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId
                + "/github/repositories/" + DemoSeedDataService.GITHUB_REPO_ID + "/sync";

        // Non-PM forbidden
        assertThat(sendJson("POST", syncPath, dev1Token, null).statusCode()).isEqualTo(403);
        assertThat(GITHUB_TOKEN_EXCHANGES.get()).isZero();

        // PM sync succeeds, verifies RS256 JWT, caches token, syncs PR/commit/CI/independent review
        HttpResponse<String> syncRes = sendJson("POST", syncPath, pmToken, null);
        assertThat(syncRes.statusCode()).isEqualTo(200);
        JsonNode syncNode = json.readTree(syncRes.body());
        assertThat(syncNode.get("prsSynced").asInt()).isEqualTo(1);
        assertThat(syncNode.get("commitsSynced").asInt()).isEqualTo(1);
        assertThat(syncNode.get("ciRunsSynced").asInt()).isEqualTo(1);
        assertThat(syncNode.get("reviewsSynced").asInt()).isEqualTo(1);
        assertThat(GITHUB_JWT_VERIFIED.get()).isTrue();
        assertThat(GITHUB_TOKEN_EXCHANGES.get()).isEqualTo(1);

        // Signed webhooks: PR #106 open -> check_run -> self-approval rejected -> independent approval accepted -> PR merge does NOT mark DSP-5 DONE
        sendSignedWebhook("e2e-del-pr-106", "pull_request", Map.of(
                "action", "opened",
                "repository", Map.of("id", DemoSeedDataService.DEMO_GITHUB_REPO_ID),
                "pull_request", Map.of(
                        "number", 106,
                        "state", "open",
                        "merged", false,
                        "title", "DSP-5 Webhook integration PR",
                        "user", Map.of("login", DemoSeedDataService.DEV1_GITHUB_LOGIN),
                        "head", Map.of("sha", SHA_WEBHOOK_PR, "ref", "feat/DSP-5-wh")
                )
        ));
        sendSignedWebhook("e2e-del-check-106", "check_run", Map.of(
                "action", "completed",
                "repository", Map.of("id", DemoSeedDataService.DEMO_GITHUB_REPO_ID),
                "check_run", Map.of(
                        "id", 96001L,
                        "name", "ci-build",
                        "head_sha", SHA_WEBHOOK_PR,
                        "status", "completed",
                        "conclusion", "success",
                        "started_at", "2026-11-24T12:10:00Z",
                        "completed_at", "2026-11-24T12:12:00Z",
                        "pull_requests", List.of(Map.of("number", 106))
                )
        ));
        assertThat(jdbc.queryForObject(
                "SELECT conclusion FROM ci_runs WHERE repository_id = ? AND github_run_id = 96001",
                String.class, DemoSeedDataService.GITHUB_REPO_ID
        )).isEqualTo("SUCCESS");

        // Self-approval rejected
        sendSignedWebhook("e2e-del-rev-self", "pull_request_review", Map.of(
                "action", "submitted",
                "repository", Map.of("id", DemoSeedDataService.DEMO_GITHUB_REPO_ID),
                "pull_request", Map.of("number", 106, "user", Map.of("login", DemoSeedDataService.DEV1_GITHUB_LOGIN), "head", Map.of("sha", SHA_WEBHOOK_PR)),
                "review", Map.of("id", 86001L, "state", "approved", "commit_id", SHA_WEBHOOK_PR, "body", "self", "user", Map.of("login", DemoSeedDataService.DEV1_GITHUB_LOGIN))
        ));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM code_reviews WHERE repository_id = ? AND pr_number = 106",
                Integer.class, DemoSeedDataService.GITHUB_REPO_ID
        )).isZero();

        // Independent approval accepted
        sendSignedWebhook("e2e-del-rev-peer", "pull_request_review", Map.of(
                "action", "submitted",
                "repository", Map.of("id", DemoSeedDataService.DEMO_GITHUB_REPO_ID),
                "pull_request", Map.of("number", 106, "user", Map.of("login", DemoSeedDataService.DEV1_GITHUB_LOGIN), "head", Map.of("sha", SHA_WEBHOOK_PR)),
                "review", Map.of("id", 86002L, "state", "approved", "commit_id", SHA_WEBHOOK_PR, "body", "peer approved", "user", Map.of("login", DemoSeedDataService.DEV2_GITHUB_LOGIN))
        ));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM code_reviews WHERE repository_id = ? AND pr_number = 106",
                String.class, DemoSeedDataService.GITHUB_REPO_ID
        )).isEqualTo("PASS");

        // Part H Invariant: PR merge does NOT mark DSP-5 DONE
        sendSignedWebhook("e2e-del-pr-106-merged", "pull_request", Map.of(
                "action", "closed",
                "repository", Map.of("id", DemoSeedDataService.DEMO_GITHUB_REPO_ID),
                "pull_request", Map.of(
                        "number", 106,
                        "state", "closed",
                        "merged", true,
                        "merged_at", "2026-11-24T12:20:00Z",
                        "title", "DSP-5 Webhook integration PR",
                        "user", Map.of("login", DemoSeedDataService.DEV1_GITHUB_LOGIN),
                        "head", Map.of("sha", SHA_WEBHOOK_PR, "ref", "feat/DSP-5-wh")
                )
        ));
        assertThat(jdbc.queryForObject(
                "SELECT state FROM github_activities WHERE repository_id = ? AND kind = 'PULL_REQUEST' AND pr_number = 106",
                String.class, DemoSeedDataService.GITHUB_REPO_ID
        )).isEqualTo("MERGED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM work_items WHERE id = ?",
                String.class, DemoSeedDataService.TASK_5_REVIEW_ID
        )).isEqualTo("IN_REVIEW");

        // =========================================================================
        // PHASE 5: Report Publish + CLIENT Real Content + S3 XLSX Export (CWE-1236) + HEAD confirmUpload
        // =========================================================================
        HttpResponse<String> createReportRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/reports",
                pmToken,
                Map.of(
                        "type", "SPRINT",
                        "periodStart", "2026-11-16",
                        "periodEnd", "2026-11-30",
                        "content", Map.of(
                                "summary", "Week 8 E2E Sprint Delivery",
                                "metrics", Map.of("velocity", 21, "formulaAttack", "=CMD()")
                        )
                )
        );
        assertThat(createReportRes.statusCode()).isEqualTo(201);
        UUID newReportId = UUID.fromString(json.readTree(createReportRes.body()).get("id").asText());

        HttpResponse<String> publishRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/reports/" + newReportId + "/publish",
                pmToken, null
        );
        assertThat(publishRes.statusCode()).isEqualTo(200);

        // CLIENT getReports returns real JSONB content and honors type filter
        HttpResponse<String> clientReportsRes = sendJson(
                "GET",
                "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/reports?type=SPRINT",
                clientToken, null
        );
        assertThat(clientReportsRes.statusCode()).isEqualTo(200);
        JsonNode clientReports = json.readTree(clientReportsRes.body());
        assertThat(clientReports).hasSize(1);
        assertThat(clientReports.get(0).get("content").get("summary").asText()).isEqualTo("Week 8 E2E Sprint Delivery");
        assertThat(clientReports.get(0).get("content").get("metrics").get("velocity").asInt()).isEqualTo(21);

        // POST .../export-attachment?format=XLSX uploads to S3 mock with CWE-1236 neutralization
        HttpResponse<String> exportXlsxRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/reports/" + newReportId + "/export-attachment?format=XLSX",
                pmToken, null
        );
        assertThat(exportXlsxRes.statusCode()).isEqualTo(201);
        JsonNode exportJson = json.readTree(exportXlsxRes.body());
        UUID exportAttachmentId = UUID.fromString(exportJson.get("attachmentId").asText());
        String exportSha = exportJson.get("sha256").asText();
        assertThat(S3_PUT_HITS.get()).isGreaterThanOrEqualTo(1);

        Map<String, Object> exportRow = jdbc.queryForMap(
                "SELECT storage_key, sha256, visibility, scan_status FROM attachments WHERE id = ?",
                exportAttachmentId
        );
        assertThat(exportRow.get("visibility")).isEqualTo("CLIENT_SHARED");
        assertThat(exportRow.get("scan_status")).isEqualTo("CLEAN");
        assertThat(exportRow.get("sha256")).isEqualTo(exportSha);

        byte[] s3XlsxBytes = S3_STORE.get("/devsphere-attachments/" + exportRow.get("storage_key"));
        assertThat(s3XlsxBytes).isNotNull();
        assertThat(sha256Hex(s3XlsxBytes)).isEqualTo(exportSha);
        assertThat(readZipEntry(s3XlsxBytes, "xl/worksheets/sheet1.xml")).contains("&apos;=CMD()");

        // AttachmentService.confirmUpload verifies S3 object via HEAD (409 when missing, 200 CLEAN once uploaded)
        byte[] sampleFile = "week8-e2e-attachment-payload".getBytes(StandardCharsets.UTF_8);
        String sampleSha = sha256Hex(sampleFile);
        HttpResponse<String> uploadReqRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/attachments/upload-request",
                pmToken,
                Map.of(
                        "filename", "evidence.txt",
                        "contentType", "text/plain",
                        "fileSize", sampleFile.length,
                        "sha256", sampleSha,
                        "kind", "REPORT_EXPORT",
                        "parentId", newReportId.toString()
                )
        );
        assertThat(uploadReqRes.statusCode()).isEqualTo(201);
        JsonNode uploadReqJson = json.readTree(uploadReqRes.body());
        UUID pendingAttachmentId = UUID.fromString(uploadReqJson.get("attachmentId").asText());
        String pendingStorageKey = uploadReqJson.get("storageKey").asText();

        HttpResponse<String> confirmMissingRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/attachments/" + pendingAttachmentId + "/confirm",
                pmToken, null
        );
        assertThat(confirmMissingRes.statusCode()).isEqualTo(409);
        assertThat(json.readTree(confirmMissingRes.body()).get("code").asText()).isEqualTo("STORAGE_OBJECT_MISSING");

        S3_STORE.put("/devsphere-attachments/" + pendingStorageKey, sampleFile);
        HttpResponse<String> confirmPresentRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/attachments/" + pendingAttachmentId + "/confirm",
                pmToken, null
        );
        assertThat(confirmPresentRes.statusCode()).isEqualTo(200);
        assertThat(json.readTree(confirmPresentRes.body()).get("scanStatus").asText()).isEqualTo("CLEAN");
        assertThat(S3_HEAD_HITS.get()).isGreaterThanOrEqualTo(2);
    }

    private String httpUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    private HttpResponse<String> sendJson(String method, String path, String token, Object body) throws Exception {
        String payload = body == null ? "" : json.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(httpUrl(path)))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private UUID createAiJob(UUID workspaceId, UUID projectId, String token, String jobType, Map<String, Object> inputRefs) throws Exception {
        HttpResponse<String> res = sendJson(
                "POST",
                "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ai/jobs",
                token,
                Map.of("jobType", jobType, "inputRefs", inputRefs)
        );
        assertThat(res.statusCode()).isEqualTo(201);
        return UUID.fromString(json.readTree(res.body()).get("id").asText());
    }

    private void sendSignedWebhook(String deliveryId, String eventType, Map<String, Object> payload) throws Exception {
        byte[] body = json.writeValueAsBytes(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));

        HttpResponse<String> res = httpClient.send(
                HttpRequest.newBuilder(URI.create(httpUrl("/api/v1/github/webhook")))
                        .header("Content-Type", "application/json")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", eventType)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(json.readTree(res.body()).get("status").asText()).isEqualTo("ok");
    }

    private StompSession connectStomp(WebSocketStompClient client, String token) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        return client.connectAsync(
                wsUrl(), new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS);
    }

    private void assertSubscribeDenied(String bearerToken, String destination) throws Exception {
        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();
        CompletableFuture<CloseStatus> closeFuture = new CompletableFuture<>();
        WebSocketStompClient client = new WebSocketStompClient(
                new CloseStatusCapturingWebSocketClient(new StandardWebSocketClient(), closeFuture)
        );
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
                        public void handleException(StompSession s, StompCommand c, StompHeaders h, byte[] p, Throwable ex) {
                            errorFuture.complete(ex);
                        }

                        @Override
                        public void handleTransportError(StompSession s, Throwable ex) {
                            errorFuture.complete(ex);
                        }
                    }
            ).get(5, TimeUnit.SECONDS);
            session.subscribe(destination, new QueueFrameHandler(new LinkedBlockingQueue<>()));
            Object outcome = CompletableFuture.anyOf(errorFuture, closeFuture).get(5, TimeUnit.SECONDS);
            assertThat(outcome).isNotNull();
        } finally {
            client.stop();
        }
    }

    private static String extractCookiePair(List<String> setCookieHeaders, String cookieName) {
        for (String header : setCookieHeaders) {
            String firstSegment = header.split(";", 2)[0].trim();
            if (firstSegment.startsWith(cookieName + "=")) {
                return firstSegment;
            }
        }
        return null;
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String wrapChatCompletion(String contentJson, int promptTokens, int completionTokens) {
        String escaped = contentJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{"
                + "\"id\":\"chatcmpl-e2e\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":" + completionTokens + "}"
                + "}";
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String readZipEntry(byte[] zipBytes, String entryName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Missing zip entry: " + entryName);
    }

    private static final class QueueFrameHandler implements StompFrameHandler {
        private final BlockingQueue<String> queue;

        private QueueFrameHandler(BlockingQueue<String> queue) {
            this.queue = queue;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            if (payload instanceof byte[] bytes) {
                queue.offer(new String(bytes, StandardCharsets.UTF_8));
            } else if (payload != null) {
                queue.offer(payload.toString());
            }
        }
    }

    private static final class CloseStatusCapturingWebSocketClient implements WebSocketClient {
        private final WebSocketClient delegate;
        private final CompletableFuture<CloseStatus> closeStatusFuture;

        private CloseStatusCapturingWebSocketClient(WebSocketClient delegate, CompletableFuture<CloseStatus> closeStatusFuture) {
            this.delegate = delegate;
            this.closeStatusFuture = closeStatusFuture;
        }

        @Override
        public CompletableFuture<WebSocketSession> execute(WebSocketHandler handler, String uriTemplate, Object... uriVariables) {
            return delegate.execute(wrap(handler), uriTemplate, uriVariables);
        }

        @Override
        public CompletableFuture<WebSocketSession> execute(WebSocketHandler handler, WebSocketHttpHeaders headers, URI uri) {
            return delegate.execute(wrap(handler), headers, uri);
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
