package com.project.devsphere;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.ai.application.AiSecretCipher;
import com.project.devsphere.ai.application.AiTaskRouter;
import com.project.devsphere.ai.domain.AiJob;
import com.project.devsphere.ai.domain.AiProviderType;
import com.project.devsphere.communication.application.ChatService;
import com.project.devsphere.communication.domain.ChatChannel;
import com.project.devsphere.identity.application.SessionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

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
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "devsphere.database.manage-runtime-privileges=false",
                "devsphere.auth.mail.from=devsphere@example.test",
                "spring.mail.host=localhost",
                "spring.mail.port=2525",
                "devsphere.auth.mail.initial-delay-ms=600000",
                "devsphere.ai.allow-loopback-self-host=true"
        }
)
class AiProviderIntegrationTest {

    private static final String TEST_PASSWORD = "A strong integration test password";

    private static HttpServer aiMockServer;
    private static int aiMockPort;
    private static final AtomicReference<MockResponder> mockResponder = new AtomicReference<>();
    private static final AtomicInteger aiMockHits = new AtomicInteger();
    private static final List<String> capturedAuthHeaders = java.util.Collections.synchronizedList(new ArrayList<>());

    private static HttpServer cloudSpyServer;
    private static int cloudSpyPort;
    private static final AtomicInteger cloudSpyHits = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionService sessionService;
    @Autowired private AiSecretCipher secretCipher;
    @Autowired private AiTaskRouter aiTaskRouter;
    @Autowired private ChatService chatService;
    @MockitoBean private JavaMailSender mailSender;

    private final ObjectMapper jsonMapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Argon2PasswordEncoder passwordEncoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

    @FunctionalInterface
    interface MockResponder {
        MockReply respond(String requestBody) throws Exception;
    }

    record MockReply(int statusCode, String bodyJson) {
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] aiKey = new byte[32];
        new SecureRandom().nextBytes(aiKey);
        String encodedAiKey = Base64.getEncoder().encodeToString(aiKey);
        registry.add("devsphere.ai.encryption-key", () -> encodedAiKey);
    }

    @BeforeAll
    static void startMockServers() throws IOException {
        aiMockServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        aiMockPort = aiMockServer.getAddress().getPort();
        aiMockServer.createContext("/v1/chat/completions", exchange -> {
            aiMockHits.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                capturedAuthHeaders.add(auth);
            }
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            MockResponder responder = mockResponder.get();
            try {
                MockReply reply = responder.respond(reqBody);
                writeJsonResponse(exchange, reply.statusCode(), reply.bodyJson());
            } catch (Exception ex) {
                writeJsonResponse(exchange, 500, "{\"error\":\"mock responder failed\"}");
            }
        });
        aiMockServer.start();

        cloudSpyServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        cloudSpyPort = cloudSpyServer.getAddress().getPort();
        cloudSpyServer.createContext("/v1/chat/completions", exchange -> {
            cloudSpyHits.incrementAndGet();
            writeJsonResponse(exchange, 200, wrapChatCompletion("{\"answer\":\"cloud\"}", 10, 5));
        });
        cloudSpyServer.start();
    }

    @AfterAll
    static void stopMockServers() {
        if (aiMockServer != null) {
            aiMockServer.stop(0);
        }
        if (cloudSpyServer != null) {
            cloudSpyServer.stop(0);
        }
    }

    @BeforeEach
    void resetState() {
        var tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class
        );
        jdbc.execute("TRUNCATE TABLE " + String.join(",", tables) + " CASCADE");
        aiMockHits.set(0);
        cloudSpyHits.set(0);
        capturedAuthHeaders.clear();
        mockResponder.set(req -> new MockReply(200, wrapChatCompletion("{\"summary\":\"ok\"}", 20, 10)));
    }

    private static void writeJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String wrapChatCompletion(String contentJson, int promptTokens, int completionTokens) {
        String escapedContent = contentJson
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return "{"
                + "\"id\":\"chatcmpl-test\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + escapedContent + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":" + completionTokens + "}"
                + "}";
    }

    private record Fixture(
            UUID workspaceId,
            UUID projectId,
            UUID ownerId,
            UUID memberId,
            String ownerToken,
            String memberToken,
            SessionService.Principal ownerPrincipal,
            SessionService.Principal memberPrincipal
    ) {
    }

    private Fixture createFixture() {
        UUID ownerId = createUser("owner-" + UUID.randomUUID() + "@example.test", "Owner");
        UUID memberId = createUser("member-" + UUID.randomUUID() + "@example.test", "Developer");

        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            jdbc.update(
                    "INSERT INTO workspaces(id, slug, name, owner_id, status) VALUES (?, ?, 'AI Router WS', ?, 'ACTIVE')",
                    workspaceId, "ws-" + workspaceId, ownerId);
            jdbc.update(
                    "INSERT INTO workspace_members(workspace_id, user_id, role, status) VALUES (?, ?, 'OWNER', 'ACTIVE'), (?, ?, 'MEMBER', 'ACTIVE')",
                    workspaceId, ownerId, workspaceId, memberId);
            jdbc.update(
                    "INSERT INTO projects(id, workspace_id, project_key, name, objective, planned_start) VALUES (?, ?, 'AI', 'AI Project', 'Ship safely', current_date)",
                    projectId, workspaceId);

            UUID ownerPm = UUID.randomUUID();
            UUID memberPm = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO project_members(id, project_id, workspace_id, user_id, status) VALUES (?, ?, ?, ?, 'ACTIVE'), (?, ?, ?, ?, 'ACTIVE')",
                    ownerPm, projectId, workspaceId, ownerId,
                    memberPm, projectId, workspaceId, memberId);
            jdbc.update(
                    "INSERT INTO project_member_roles(project_id, project_member_id, role, granted_by, accepted_by, accepted_at) VALUES (?, ?, 'PM', ?, ?, now())",
                    projectId, ownerPm, ownerId, ownerId);
            jdbc.update(
                    "INSERT INTO project_member_roles(project_id, project_member_id, role, granted_by) VALUES (?, ?, 'DEVELOPER', ?)",
                    projectId, memberPm, ownerId);
        });

        String ownerEmail = jdbc.queryForObject("SELECT email::text FROM users WHERE id = ?", String.class, ownerId);
        String memberEmail = jdbc.queryForObject("SELECT email::text FROM users WHERE id = ?", String.class, memberId);
        String ownerToken = sessionService.login(ownerEmail, TEST_PASSWORD).access().value();
        String memberToken = sessionService.login(memberEmail, TEST_PASSWORD).access().value();

        return new Fixture(
                workspaceId,
                projectId,
                ownerId,
                memberId,
                ownerToken,
                memberToken,
                sessionService.authenticate(ownerToken).orElseThrow(),
                sessionService.authenticate(memberToken).orElseThrow()
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

    private HttpResponse<String> sendJson(String method, String path, String token, Object body) throws Exception {
        String payload = body == null ? "" : jsonMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
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

    private String vllmBaseUrl() {
        return "http://127.0.0.1:" + aiMockPort;
    }

    private void configureDualConnectionsAndRoutes(Fixture f, Map<String, String> routes) throws Exception {
        HttpResponse<String> connRes = sendJson(
                "PUT",
                "/api/v1/workspaces/" + f.workspaceId() + "/ai/config/connections",
                f.ownerToken(),
                Map.of("connections", List.of(
                        Map.of(
                                "id", "openai-cloud",
                                "provider", "OPENAI",
                                "baseUrl", "https://api.openai.com",
                                "model", "gpt-4o",
                                "enabled", true,
                                "apiKey", "test-mock-cloud-key"
                        ),
                        Map.of(
                                "id", "vllm-local",
                                "provider", "VLLM_SELF_HOST",
                                "baseUrl", vllmBaseUrl(),
                                "model", "qwen-2.5-coder",
                                "enabled", true,
                                "apiKey", "vllm-local-token"
                        )
                ))
        );
        assertThat(connRes.statusCode()).isEqualTo(200);

        HttpResponse<String> routeRes = sendJson(
                "PUT",
                "/api/v1/workspaces/" + f.workspaceId() + "/ai/config/routes",
                f.ownerToken(),
                Map.of("routes", routes)
        );
        assertThat(routeRes.statusCode()).isEqualTo(200);
    }

    private UUID createAiJobViaApi(Fixture f, String jobType, Map<String, Object> inputRefs) throws Exception {
        HttpResponse<String> res = sendJson(
                "POST",
                "/api/v1/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/ai/jobs",
                f.memberToken(),
                Map.of(
                        "jobType", jobType,
                        "inputRefs", inputRefs
                )
        );
        assertThat(res.statusCode()).isEqualTo(201);
        return UUID.fromString(jsonMapper.readTree(res.body()).get("id").asText());
    }

    @Test
    @DisplayName("Config API enforces owner RBAC, SSRF guard, encryptedCredential DB storage (V006), and credential preservation")
    void configEndpointsEnforceRbacSsrfEncryptionAndPreservation() throws Exception {
        Fixture f = createFixture();

        // 1. SSRF metadata & insecure OPENAI rejected with 400
        HttpResponse<String> ssrfRes = sendJson(
                "PUT",
                "/api/v1/workspaces/" + f.workspaceId() + "/ai/config/connections",
                f.ownerToken(),
                Map.of("connections", List.of(
                        Map.of("id", "bad-meta", "provider", "VLLM_SELF_HOST", "baseUrl", "http://169.254.169.254", "model", "m", "enabled", true)
                ))
        );
        assertThat(ssrfRes.statusCode()).isEqualTo(400);

        HttpResponse<String> badCloudRes = sendJson(
                "PUT",
                "/api/v1/workspaces/" + f.workspaceId() + "/ai/config/connections",
                f.ownerToken(),
                Map.of("connections", List.of(
                        Map.of("id", "bad-cloud", "provider", "OPENAI", "baseUrl", "http://127.0.0.1:" + aiMockPort, "model", "gpt-4o", "enabled", true)
                ))
        );
        assertThat(badCloudRes.statusCode()).isEqualTo(400);

        // 2. Non-owner member cannot modify connections, routes, or privacy (403 Forbidden)
        HttpResponse<String> memberPutConn = sendJson(
                "PUT",
                "/api/v1/workspaces/" + f.workspaceId() + "/ai/config/connections",
                f.memberToken(),
                Map.of("connections", List.of())
        );
        assertThat(memberPutConn.statusCode()).isEqualTo(403);

        HttpResponse<String> memberPutPrivacy = sendJson(
                "PUT",
                "/api/v1/workspaces/" + f.workspaceId() + "/ai/config/privacy",
                f.memberToken(),
                Map.of("privacyPolicy", "SELF_HOST_ONLY")
        );
        assertThat(memberPutPrivacy.statusCode()).isEqualTo(403);

        // 3. Owner configures connections with secret keys -> stored encrypted under encryptedCredential (passes ds_json_has_secret)
        configureDualConnectionsAndRoutes(f, Map.of("REQUIREMENT_PLAN", "vllm-local"));

        String rawDbConnections = jdbc.queryForObject(
                "SELECT ai_connections::text FROM workspaces WHERE id = ?",
                String.class,
                f.workspaceId()
        );
        assertThat(rawDbConnections)
                .contains("\"encryptedCredential\"")
                .contains("v1:")
                .doesNotContain("test-mock-cloud-key")
                .doesNotContain("vllm-local-token")
                .doesNotContain("\"apiKey\"");

        // 4. Member GET returns redacted view with apiKeyConfigured=true and zero secrets
        HttpResponse<String> memberGet = sendJson(
                "GET",
                "/api/v1/workspaces/" + f.workspaceId() + "/ai/config",
                f.memberToken(),
                null
        );
        assertThat(memberGet.statusCode()).isEqualTo(200);
        assertThat(memberGet.body())
                .doesNotContain("test-mock-cloud-key")
                .doesNotContain("vllm-local-token")
                .doesNotContain("encryptedCredential");
        JsonNode getRoot = jsonMapper.readTree(memberGet.body());
        assertThat(getRoot.get("privacyPolicy").asText()).isEqualTo("CLOUD_ALLOWED");
        assertThat(getRoot.get("connections").get(0).get("apiKeyConfigured").asBoolean()).isTrue();
        assertThat(getRoot.get("routes").get("REQUIREMENT_PLAN").asText()).isEqualTo("vllm-local");

        // 5. Updating connection without apiKey preserves existing encryptedCredential
        HttpResponse<String> updateWithoutKey = sendJson(
                "PUT",
                "/api/v1/workspaces/" + f.workspaceId() + "/ai/config/connections",
                f.ownerToken(),
                Map.of("connections", List.of(
                        Map.of(
                                "id", "openai-cloud",
                                "provider", "OPENAI",
                                "baseUrl", "https://api.openai.com",
                                "model", "gpt-4.1",
                                "enabled", true
                        )
                ))
        );
        assertThat(updateWithoutKey.statusCode()).isEqualTo(200);
        assertThat(jsonMapper.readTree(updateWithoutKey.body()).get("connections").get(0).get("apiKeyConfigured").asBoolean())
                .isTrue();

        List<Map<String, Object>> parsedDb = jsonMapper.readValue(
                jdbc.queryForObject("SELECT ai_connections::text FROM workspaces WHERE id = ?", String.class, f.workspaceId()),
                new TypeReference<>() {}
        );
        assertThat(secretCipher.decrypt((String) parsedDb.get(0).get("encryptedCredential"))).isEqualTo("test-mock-cloud-key");
    }

    @Test
    @DisplayName("TC-21 & TC-22: Executes VLLM_SELF_HOST over HTTP, and blocks OPENAI under SELF_HOST_ONLY with 503 AI_UNAVAILABLE and 0 HTTP calls")
    void executesSelfHostAndBlocksCloudUnderSelfHostOnly() throws Exception {
        Fixture f = createFixture();
        configureDualConnectionsAndRoutes(f, Map.of(
                "REQUIREMENT_PLAN", "vllm-local",
                "CHAT_ANSWER", "openai-cloud"
        ));

        // TC-21: Verify CLOUD_ALLOWED resolves openai-cloud for CHAT_ANSWER and executes vllm-local for REQUIREMENT_PLAN
        assertThat(aiTaskRouter.resolve(f.workspaceId(), AiJob.JobType.CHAT_ANSWER).provider())
                .isEqualTo(AiProviderType.OPENAI);

        mockResponder.set(req -> new MockReply(
                200,
                wrapChatCompletion("{\"items\":[{\"tempKey\":\"T1\",\"estimateMinutes\":90}]}", 120, 45)
        ));
        UUID planJobId = createAiJobViaApi(f, "REQUIREMENT_PLAN", Map.of("schemaVersion", 1, "requirement", "Build auth"));
        HttpResponse<String> execPlan = sendJson(
                "POST",
                "/api/v1/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/ai/jobs/" + planJobId + "/execute",
                f.memberToken(),
                null
        );
        assertThat(execPlan.statusCode()).isEqualTo(200);
        JsonNode planNode = jsonMapper.readTree(execPlan.body());
        assertThat(planNode.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(planNode.get("validationStatus").asText()).isEqualTo("VALID");
        assertThat(planNode.get("tokensIn").asInt()).isEqualTo(120);
        assertThat(planNode.get("tokensOut").asInt()).isEqualTo(45);
        assertThat(capturedAuthHeaders).contains("Bearer vllm-local-token");

        // TC-22: Switch to SELF_HOST_ONLY -> OPENAI route strictly fails with 503 AI_UNAVAILABLE before any HTTP call
        HttpResponse<String> privacyRes = sendJson(
                "PUT",
                "/api/v1/workspaces/" + f.workspaceId() + "/ai/config/privacy",
                f.ownerToken(),
                Map.of("privacyPolicy", "SELF_HOST_ONLY")
        );
        assertThat(privacyRes.statusCode()).isEqualTo(200);

        UUID cloudChatJobId = createAiJobViaApi(f, "CHAT_ANSWER", Map.of("schemaVersion", 1, "prompt", "Hello"));
        HttpResponse<String> blockedExec = sendJson(
                "POST",
                "/api/v1/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/ai/jobs/" + cloudChatJobId + "/execute",
                f.memberToken(),
                null
        );
        assertThat(blockedExec.statusCode()).isEqualTo(503);
        assertThat(jsonMapper.readTree(blockedExec.body()).get("code").asText()).isEqualTo("AI_UNAVAILABLE");
        Map<String, Object> failedRow = jdbc.queryForMap(
                "SELECT status, validation_status, error_code FROM ai_jobs WHERE id = ?",
                cloudChatJobId
        );
        assertThat(failedRow.get("status")).isEqualTo("FAILED");
        assertThat(failedRow.get("validation_status")).isEqualTo("PENDING");
        assertThat(failedRow.get("error_code")).isEqualTo("AI_UNAVAILABLE");

        // Extra proof with DB-seeded OPENAI pointing to loopback spy server: 0 outbound HTTP requests
        String spyConnJson = jsonMapper.writeValueAsString(List.of(Map.of(
                "id", "openai-cloud",
                "provider", "OPENAI",
                "baseUrl", "http://127.0.0.1:" + cloudSpyPort,
                "model", "gpt-4o",
                "enabled", true
        )));
        jdbc.update("UPDATE workspaces SET ai_connections = ?::jsonb WHERE id = ?", spyConnJson, f.workspaceId());
        assertThatThrownBy(() -> aiTaskRouter.resolve(f.workspaceId(), AiJob.JobType.CHAT_ANSWER))
                .hasMessageContaining("SELF_HOST_ONLY");
        assertThat(cloudSpyHits.get()).isZero();
    }

    @Test
    @DisplayName("TC-23, TC-24, TC-25 & 503 resilience: ValidationGate rejects invalid plans/reviews, flags fabricated report metrics NEEDS_REVIEW, and handles provider 500")
    void validationGateAndProviderFailureIntegration() throws Exception {
        Fixture f = createFixture();
        configureDualConnectionsAndRoutes(f, Map.of(
                "REQUIREMENT_PLAN", "vllm-local",
                "PR_REVIEW", "vllm-local",
                "REPORT_SUMMARY", "vllm-local"
        ));

        // TC-23a: Negative estimate -> FAILED + INVALID
        mockResponder.set(req -> new MockReply(
                200,
                wrapChatCompletion("{\"items\":[{\"tempKey\":\"T1\",\"estimateMinutes\":-30}]}", 50, 20)
        ));
        UUID negPlanId = createAiJobViaApi(f, "REQUIREMENT_PLAN", Map.of("schemaVersion", 1));
        HttpResponse<String> negPlanRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/ai/jobs/" + negPlanId + "/execute",
                f.memberToken(),
                null
        );
        assertThat(negPlanRes.statusCode()).isEqualTo(200);
        JsonNode negPlanJson = jsonMapper.readTree(negPlanRes.body());
        assertThat(negPlanJson.get("status").asText()).isEqualTo("FAILED");
        assertThat(negPlanJson.get("validationStatus").asText()).isEqualTo("INVALID");
        assertThat(negPlanJson.get("validationReport").get("invalidEstimates")).hasSize(1);

        // TC-23b: Dependency cycle -> FAILED + INVALID + dependencyCycleDetected
        mockResponder.set(req -> new MockReply(
                200,
                wrapChatCompletion(
                        "{\"items\":[{\"tempKey\":\"T1\",\"estimateMinutes\":30},{\"tempKey\":\"T2\",\"estimateMinutes\":30}],"
                                + "\"dependencies\":[{\"predecessorKey\":\"T1\",\"successorKey\":\"T2\"},{\"predecessorKey\":\"T2\",\"successorKey\":\"T1\"}]}",
                        50, 20)
        ));
        UUID cyclePlanId = createAiJobViaApi(f, "REQUIREMENT_PLAN", Map.of("schemaVersion", 1));
        HttpResponse<String> cyclePlanRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/ai/jobs/" + cyclePlanId + "/execute",
                f.memberToken(),
                null
        );
        JsonNode cycleJson = jsonMapper.readTree(cyclePlanRes.body());
        assertThat(cycleJson.get("status").asText()).isEqualTo("FAILED");
        assertThat(cycleJson.get("validationStatus").asText()).isEqualTo("INVALID");
        assertThat(cycleJson.get("validationReport").get("dependencyCycleDetected").asBoolean()).isTrue();

        // TC-24: PR_REVIEW out-of-bounds finding rejected, in-bounds finding accepted
        mockResponder.set(req -> new MockReply(
                200,
                wrapChatCompletion("{\"status\":\"REQUIRES_CHANGES\",\"findings\":[{\"filePath\":\"src/App.java\",\"lineNumber\":200,\"message\":\"Out of range\"}]}", 40, 15)
        ));
        UUID badReviewId = createAiJobViaApi(f, "PR_REVIEW", Map.of(
                "schemaVersion", 1,
                "diffFiles", Map.of("src/App.java", 30)
        ));
        HttpResponse<String> badReviewRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/ai/jobs/" + badReviewId + "/execute",
                f.memberToken(),
                null
        );
        JsonNode badReviewJson = jsonMapper.readTree(badReviewRes.body());
        assertThat(badReviewJson.get("status").asText()).isEqualTo("FAILED");
        assertThat(badReviewJson.get("validationStatus").asText()).isEqualTo("INVALID");
        assertThat(badReviewJson.get("validationReport").get("invalidFindings")).hasSize(1);

        // TC-25: Seed real DB facts (2 work items: 1 DONE, 1 TO_DO; 1 open alert; 1 active sprint)
        tx.executeWithoutResult(status -> {
            UUID doneTaskId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO work_items(id, project_id, type, title, status) VALUES (?, ?, 'TASK', 'Done task', 'TO_DO')",
                    doneTaskId, f.projectId());
            jdbc.update("UPDATE work_items SET status = 'IN_PROGRESS', started_at = now() WHERE id = ?", doneTaskId);
            jdbc.update("UPDATE work_items SET status = 'IN_REVIEW' WHERE id = ?", doneTaskId);
            jdbc.update("UPDATE work_items SET status = 'DONE', completed_at = now(), accepted_by = ? WHERE id = ?", f.ownerId(), doneTaskId);

            jdbc.update(
                    "INSERT INTO work_items(id, project_id, type, title, status) VALUES (?, ?, 'TASK', 'Open task', 'TO_DO')",
                    UUID.randomUUID(), f.projectId());
            jdbc.update(
                    "INSERT INTO sprints(id, project_id, name, goal, start_date, end_date, status) VALUES (?, ?, 'S1', 'Goal', current_date, current_date + 14, 'ACTIVE')",
                    UUID.randomUUID(), f.projectId());
            jdbc.update(
                    "INSERT INTO alerts(id, project_id, fingerprint, alert_code, severity, status, title) VALUES (?, ?, ?, 'CRITICAL_PATH_SLIP', 'HIGH', 'NEW', 'Delay risk')",
                    UUID.randomUUID(), f.projectId(), "a".repeat(64));
        });

        mockResponder.set(req -> new MockReply(
                200,
                wrapChatCompletion(
                        "{\"summary\":\"Fabricated metrics\",\"metrics\":{\"totalWorkItems\":99,\"completedWorkItems\":99,\"openAlerts\":0,\"activeSprints\":1}}",
                        60, 25)
        ));
        UUID fabricatedReportJobId = createAiJobViaApi(f, "REPORT_SUMMARY", Map.of("schemaVersion", 1));
        HttpResponse<String> fabricatedReportRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/ai/jobs/" + fabricatedReportJobId + "/execute",
                f.memberToken(),
                null
        );
        JsonNode fabricatedNode = jsonMapper.readTree(fabricatedReportRes.body());
        assertThat(fabricatedNode.get("status").asText()).isEqualTo("FAILED");
        assertThat(fabricatedNode.get("validationStatus").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(fabricatedNode.get("validationReport").get("factMismatches")).hasSize(3);

        // Accurate metrics pass VALID
        mockResponder.set(req -> new MockReply(
                200,
                wrapChatCompletion(
                        "{\"summary\":\"Accurate metrics\",\"metrics\":{\"totalWorkItems\":2,\"completedWorkItems\":1,\"openAlerts\":1,\"activeSprints\":1}}",
                        60, 25)
        ));
        UUID accurateReportJobId = createAiJobViaApi(f, "REPORT_SUMMARY", Map.of("schemaVersion", 1));
        HttpResponse<String> accurateReportRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/ai/jobs/" + accurateReportJobId + "/execute",
                f.memberToken(),
                null
        );
        JsonNode accurateNode = jsonMapper.readTree(accurateReportRes.body());
        assertThat(accurateNode.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(accurateNode.get("validationStatus").asText()).isEqualTo("VALID");

        // Provider HTTP 500 -> 503 AI_UNAVAILABLE
        mockResponder.set(req -> new MockReply(500, "{\"error\":\"upstream overloaded\"}"));
        UUID failingJobId = createAiJobViaApi(f, "REQUIREMENT_PLAN", Map.of("schemaVersion", 1));
        HttpResponse<String> failingRes = sendJson(
                "POST",
                "/api/v1/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/ai/jobs/" + failingJobId + "/execute",
                f.memberToken(),
                null
        );
        assertThat(failingRes.statusCode()).isEqualTo(503);
        assertThat(jsonMapper.readTree(failingRes.body()).get("code").asText()).isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    @DisplayName("@ProjectAI triggers best-effort post-commit AI reply with STOMP broadcast and never rolls back user message on failure")
    void projectAiChatMentionBestEffortAndRealtimeBroadcast() throws Exception {
        Fixture f = createFixture();
        configureDualConnectionsAndRoutes(f, Map.of("CHAT_ANSWER", "vllm-local"));

        ChatChannel teamChannel = chatService.createChannel(
                f.ownerPrincipal(), f.workspaceId(), f.projectId(), ChatChannel.Type.TEAM, "ai-collab", null);
        chatService.addMember(f.ownerPrincipal(), f.workspaceId(), f.projectId(), teamChannel.getId(), f.memberId());

        mockResponder.set(req -> new MockReply(
                200,
                wrapChatCompletion("{\"reply\":\"Sprint velocity looks healthy with zero blockers.\"}", 35, 18)
        ));

        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
        BlockingQueue<String> chatFrames = new LinkedBlockingQueue<>();

        try {
            StompHeaders connectHeaders = new StompHeaders();
            connectHeaders.add("Authorization", "Bearer " + f.memberToken());
            StompSession session = stompClient.connectAsync(
                    "ws://localhost:" + port + "/ws",
                    new WebSocketHttpHeaders(),
                    connectHeaders,
                    new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS);

            String dest = "/topic/workspaces/" + f.workspaceId() + "/projects/" + f.projectId() + "/chat/" + teamChannel.getId();
            session.subscribe(dest, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    chatFrames.offer(new String((byte[]) payload, StandardCharsets.UTF_8));
                }
            });
            Thread.sleep(250);

            HttpResponse<String> sendRes = sendJson(
                    "POST",
                    "/api/v1/workspaces/" + f.workspaceId() + "/projects/" + f.projectId()
                            + "/chat/channels/" + teamChannel.getId() + "/messages",
                    f.memberToken(),
                    Map.of("body", "Hey @ProjectAI what is our sprint status?")
            );
            assertThat(sendRes.statusCode()).isEqualTo(201);
            UUID userMsgId = UUID.fromString(jsonMapper.readTree(sendRes.body()).get("id").asText());

            // Both user message and AI reply exist in PostgreSQL
            List<Map<String, Object>> messages = jdbc.queryForList(
                    "SELECT id, sender_id, sender_kind, ai_job_id, reply_to_id, body FROM chat_messages WHERE channel_id = ? ORDER BY sent_at ASC",
                    teamChannel.getId()
            );
            assertThat(messages).hasSize(2);
            Map<String, Object> aiMsg = messages.get(1);
            assertThat(aiMsg.get("sender_kind")).isEqualTo("AI");
            assertThat(aiMsg.get("sender_id")).isNull();
            assertThat(aiMsg.get("ai_job_id")).isNotNull();
            assertThat(aiMsg.get("reply_to_id")).isEqualTo(userMsgId);
            assertThat(aiMsg.get("body")).isEqualTo("Sprint velocity looks healthy with zero blockers.");

            // Both messages delivered via STOMP WebSocket
            String frame1 = chatFrames.poll(5, TimeUnit.SECONDS);
            String frame2 = chatFrames.poll(5, TimeUnit.SECONDS);
            assertThat(frame1).isNotNull();
            assertThat(frame2).isNotNull();
            assertThat(frame1 + "\n" + frame2)
                    .contains("Hey @ProjectAI what is our sprint status?")
                    .contains("Sprint velocity looks healthy with zero blockers.");

            // Failure resilience: when AI provider returns HTTP 500, user message still commits cleanly
            mockResponder.set(req -> new MockReply(500, "{\"error\":\"down\"}"));
            HttpResponse<String> resilientSend = sendJson(
                    "POST",
                    "/api/v1/workspaces/" + f.workspaceId() + "/projects/" + f.projectId()
                            + "/chat/channels/" + teamChannel.getId() + "/messages",
                    f.memberToken(),
                    Map.of("body", "@ProjectAI please retry even if AI is down")
            );
            assertThat(resilientSend.statusCode()).isEqualTo(201);

            Integer totalCount = jdbc.queryForObject(
                    "SELECT count(*) FROM chat_messages WHERE channel_id = ?",
                    Integer.class,
                    teamChannel.getId()
            );
            assertThat(totalCount).isEqualTo(3);
        } finally {
            stompClient.stop();
        }
    }
}
