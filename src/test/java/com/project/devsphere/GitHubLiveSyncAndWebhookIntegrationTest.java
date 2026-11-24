package com.project.devsphere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.gitintegration.infrastructure.GitHubInstallationTokenService;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.operations.application.ClaimedJob;
import com.project.devsphere.operations.application.JobResult;
import com.project.devsphere.operations.application.handlers.GitHubBackfillJobHandler;
import com.project.devsphere.operations.domain.BackgroundJob;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "spring.mail.host=localhost",
        "spring.mail.port=2525",
        "devsphere.auth.mail.initial-delay-ms=600000",
        "devsphere.github.webhook-secret=test-live-sync-webhook-key-999",
        "devsphere.quartz.auto-startup=false",
        "spring.quartz.auto-startup=false"
})
@AutoConfigureMockMvc
public class GitHubLiveSyncAndWebhookIntegrationTest {

    private static final String WEBHOOK_SECRET = "test-live-sync-webhook-key-999";
    private static final String SHA_CURRENT = "1111111111111111111111111111111111111111";
    private static final String SHA_STALE   = "2222222222222222222222222222222222222222";
    private static final String SHA_COMMIT  = "3333333333333333333333333333333333333333";

    private static KeyPair rsaKeyPair;
    private static String pkcs8PemEscaped;
    private static HttpServer githubMockServer;

    private static final AtomicInteger TOKEN_EXCHANGE_CALLS = new AtomicInteger(0);
    private static final AtomicBoolean JWT_SIGNATURE_VERIFIED = new AtomicBoolean(false);
    private static final AtomicInteger MOCK_API_FORCE_STATUS = new AtomicInteger(0);
    private static final AtomicReference<String> MOCK_PRS_JSON = new AtomicReference<>("[]");
    private static final AtomicReference<String> MOCK_COMMITS_JSON = new AtomicReference<>("[]");
    private static final AtomicReference<String> MOCK_CHECKS_JSON = new AtomicReference<>("{\"total_count\":0,\"check_runs\":[]}");
    private static final AtomicReference<String> MOCK_REVIEWS_JSON = new AtomicReference<>("[]");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionTokens sessionTokens;
    @Autowired private GitHubInstallationTokenService installationTokenService;
    @Autowired private GitHubBackfillJobHandler backfillJobHandler;
    @MockitoBean private JavaMailSender mailSender;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SecureRandom random = new SecureRandom();

    @BeforeAll
    static void startMockGitHubServer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        rsaKeyPair = generator.generateKeyPair();
        String base64Key = Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(rsaKeyPair.getPrivate().getEncoded());
        pkcs8PemEscaped = ("-----BEGIN PRIVATE KEY-----\n" + base64Key + "\n-----END PRIVATE KEY-----")
                .replace("\n", "\\n");

        githubMockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        githubMockServer.createContext("/app/installations/", exchange -> {
            int forced = MOCK_API_FORCE_STATUS.get();
            if (forced >= 400) {
                if (forced == 429) {
                    exchange.getResponseHeaders().add("Retry-After", "30");
                }
                writeJson(exchange, forced, "{\"message\":\"rate limited or unavailable\"}");
                return;
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String jwt = auth.substring("Bearer ".length());
                String[] parts = jwt.split("\\.");
                if (parts.length == 3) {
                    try {
                        Signature verifier = Signature.getInstance("SHA256withRSA");
                        verifier.initVerify(rsaKeyPair.getPublic());
                        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                        if (verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                            JWT_SIGNATURE_VERIFIED.set(true);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            int count = TOKEN_EXCHANGE_CALLS.incrementAndGet();
            writeJson(exchange, 201, "{\"token\":\"ghs_live_sync_" + count + "\",\"expires_at\":\"2099-01-01T00:00:00Z\"}");
        });

        githubMockServer.createContext("/repos/", exchange -> {
            int forced = MOCK_API_FORCE_STATUS.get();
            if (forced >= 400) {
                if (forced == 429) {
                    exchange.getResponseHeaders().add("Retry-After", "60");
                } else if (forced == 403) {
                    exchange.getResponseHeaders().add("X-RateLimit-Remaining", "0");
                    exchange.getResponseHeaders().add("X-RateLimit-Reset", String.valueOf(Instant.now().getEpochSecond() + 120));
                }
                writeJson(exchange, forced, "{\"message\":\"API rate limit exceeded\"}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/pulls")) {
                writeJson(exchange, 200, MOCK_PRS_JSON.get());
            } else if (path.endsWith("/commits")) {
                writeJson(exchange, 200, MOCK_COMMITS_JSON.get());
            } else if (path.endsWith("/check-runs")) {
                writeJson(exchange, 200, MOCK_CHECKS_JSON.get());
            } else if (path.endsWith("/reviews")) {
                writeJson(exchange, 200, MOCK_REVIEWS_JSON.get());
            } else {
                writeJson(exchange, 404, "{\"message\":\"not found\"}");
            }
        });
        githubMockServer.start();
    }

    @AfterAll
    static void stopMockGitHubServer() {
        if (githubMockServer != null) {
            githubMockServer.stop(0);
        }
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] sessionKey = new byte[32];
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        sessionKey[0] = 31;
        hmac[0] = 17;
        encryption[0] = 23;
        registry.add("devsphere.auth.session.signing-key", () -> Base64.getEncoder().encodeToString(sessionKey));
        registry.add("devsphere.auth.otp.hmac-key", () -> Base64.getEncoder().encodeToString(hmac));
        registry.add("devsphere.auth.otp.encryption-key", () -> Base64.getEncoder().encodeToString(encryption));
        registry.add("devsphere.github.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("devsphere.github.app.enabled", () -> "true");
        registry.add("devsphere.github.app.app-id", () -> "88001");
        registry.add("devsphere.github.app.private-key-pem", () -> pkcs8PemEscaped);
        registry.add("devsphere.github.app.api-base-url", () -> "http://127.0.0.1:" + githubMockServer.getAddress().getPort());
        registry.add("devsphere.github.app.allow-loopback-api", () -> "true");
    }

    @BeforeEach
    void resetState() {
        TOKEN_EXCHANGE_CALLS.set(0);
        JWT_SIGNATURE_VERIFIED.set(false);
        MOCK_API_FORCE_STATUS.set(0);
        installationTokenService.invalidateToken(12345L);
        var tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class
        );
        jdbc.execute("TRUNCATE TABLE " + String.join(",", tables) + " CASCADE");
    }

    @Test
    @DisplayName("Live /sync verifies RS256 JWT, caches token, enforces PM-only RBAC, syncs PRs/commits/CI/reviews idempotently")
    void liveSync_fullHappyPathAndPmOnlyRbac() throws Exception {
        Fixture f = createFixture();

        MOCK_PRS_JSON.set(json.writeValueAsString(List.of(Map.of(
                "number", 101,
                "state", "open",
                "title", f.itemKey + " Implement GitHub App sync",
                "body", "Closes " + f.itemKey,
                "updated_at", "2026-11-24T10:00:00Z",
                "user", Map.of("login", "dev-author"),
                "head", Map.of("sha", SHA_CURRENT, "ref", "feat/" + f.itemKey)
        ))));
        MOCK_COMMITS_JSON.set(json.writeValueAsString(List.of(Map.of(
                "sha", SHA_COMMIT,
                "author", Map.of("login", "dev-author"),
                "commit", Map.of(
                        "message", f.itemKey + " add client",
                        "author", Map.of("name", "Dev Author", "date", "2026-11-24T09:50:00Z")
                )
        ))));
        MOCK_CHECKS_JSON.set(json.writeValueAsString(Map.of(
                "total_count", 1,
                "check_runs", List.of(Map.of(
                        "id", 90001L,
                        "name", "maven-verify",
                        "head_sha", SHA_CURRENT,
                        "status", "completed",
                        "conclusion", "success",
                        "started_at", "2026-11-24T10:01:00Z",
                        "completed_at", "2026-11-24T10:03:00Z",
                        "pull_requests", List.of(Map.of("number", 101))
                ))
        )));
        MOCK_REVIEWS_JSON.set(json.writeValueAsString(List.of(
                // Eligible independent developer approval
                Map.of(
                        "id", 70001L,
                        "state", "APPROVED",
                        "commit_id", SHA_CURRENT,
                        "body", "Looks solid",
                        "submitted_at", "2026-11-24T10:05:00Z",
                        "user", Map.of("login", "dev-peer")
                ),
                // Self-approval by PR author -> must be rejected
                Map.of(
                        "id", 70002L,
                        "state", "APPROVED",
                        "commit_id", SHA_CURRENT,
                        "body", "Self approve",
                        "submitted_at", "2026-11-24T10:06:00Z",
                        "user", Map.of("login", "dev-author")
                ),
                // Stale SHA review -> must be rejected
                Map.of(
                        "id", 70003L,
                        "state", "APPROVED",
                        "commit_id", SHA_STALE,
                        "body", "Stale SHA",
                        "submitted_at", "2026-11-24T10:07:00Z",
                        "user", Map.of("login", "dev-peer")
                )
        )));

        String syncUrl = "/api/v1/workspaces/" + f.workspaceId + "/projects/" + f.projectId
                + "/github/repositories/" + f.repositoryId + "/sync";

        // 1. Non-PM (Developer) is forbidden
        mvc.perform(post(syncUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.devPeerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(TOKEN_EXCHANGE_CALLS.get()).isEqualTo(0);

        // 2. PM triggers live sync -> succeeds and verifies RS256 JWT
        mvc.perform(post(syncUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.pmToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryId").value(f.repositoryId.toString()))
                .andExpect(jsonPath("$.prsSynced").value(1))
                .andExpect(jsonPath("$.commitsSynced").value(1))
                .andExpect(jsonPath("$.ciRunsSynced").value(1))
                .andExpect(jsonPath("$.reviewsSynced").value(1))
                .andExpect(jsonPath("$.syncedAt").isNotEmpty());

        assertThat(JWT_SIGNATURE_VERIFIED.get()).isTrue();
        assertThat(TOKEN_EXCHANGE_CALLS.get()).isEqualTo(1);

        // Verify persisted rows
        assertThat(jdbc.queryForObject("SELECT last_synced_at FROM github_repositories WHERE id = ?", Object.class, f.repositoryId))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_activities WHERE repository_id = ? AND kind = 'PULL_REQUEST'", Integer.class, f.repositoryId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_activities WHERE repository_id = ? AND kind = 'COMMIT'", Integer.class, f.repositoryId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_activities WHERE repository_id = ? AND kind = 'HUMAN_REVIEW'", Integer.class, f.repositoryId))
                .isEqualTo(1);

        Map<String, Object> ciRow = jdbc.queryForMap("SELECT status, conclusion, head_sha, pr_number, work_item_id FROM ci_runs WHERE repository_id = ? AND github_run_id = 90001", f.repositoryId);
        assertThat(ciRow.get("status")).isEqualTo("COMPLETED");
        assertThat(ciRow.get("conclusion")).isEqualTo("SUCCESS");
        assertThat(((String) ciRow.get("head_sha")).trim()).isEqualTo(SHA_CURRENT);
        assertThat(ciRow.get("pr_number")).isEqualTo(101);
        assertThat(ciRow.get("work_item_id")).isEqualTo(f.workItemId);

        List<Map<String, Object>> reviewRows = jdbc.queryForList(
                "SELECT status, reviewer_kind, scope, reviewer_id, head_sha FROM code_reviews WHERE repository_id = ?", f.repositoryId);
        assertThat(reviewRows).hasSize(1);
        assertThat(reviewRows.get(0).get("status")).isEqualTo("PASS");
        assertThat(reviewRows.get(0).get("reviewer_kind")).isEqualTo("HUMAN");
        assertThat(reviewRows.get(0).get("scope")).isEqualTo("PR");
        assertThat(reviewRows.get(0).get("reviewer_id")).isEqualTo(f.devPeerId);
        assertThat(((String) reviewRows.get(0).get("head_sha")).trim()).isEqualTo(SHA_CURRENT);

        // 3. Re-running /sync is idempotent and reuses cached installation token (no extra token exchange!)
        mvc.perform(post(syncUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.pmToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewsSynced").value(1));

        assertThat(TOKEN_EXCHANGE_CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_runs WHERE repository_id = ?", Integer.class, f.repositoryId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM code_reviews WHERE repository_id = ?", Integer.class, f.repositoryId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Webhooks process check_run, workflow_run, pull_request_review (enforcing independence/SHA/role) and Part H PR-merge invariant")
    void webhooks_ciRunsHumanReviewsAndPartHInvariant() throws Exception {
        Fixture f = createFixture();

        // 1. PR opened webhook
        sendSignedWebhook("del-pr-open-1", "pull_request", Map.of(
                "action", "opened",
                "repository", Map.of("id", f.githubRepoId),
                "pull_request", Map.of(
                        "number", 55,
                        "state", "open",
                        "merged", false,
                        "title", f.itemKey + " Webhook PR",
                        "user", Map.of("login", "dev-author"),
                        "head", Map.of("sha", SHA_CURRENT, "ref", "feature/" + f.itemKey)
                )
        ));

        // 2. check_run queued -> then completed (idempotent upsert on (repository_id, github_run_id, run_attempt))
        sendSignedWebhook("del-check-queued", "check_run", Map.of(
                "action", "created",
                "repository", Map.of("id", f.githubRepoId),
                "check_run", Map.of(
                        "id", 55001L,
                        "name", "unit-tests",
                        "head_sha", SHA_CURRENT,
                        "status", "queued",
                        "pull_requests", List.of(Map.of("number", 55))
                )
        ));
        assertThat(jdbc.queryForObject("SELECT status FROM ci_runs WHERE repository_id = ? AND github_run_id = 55001", String.class, f.repositoryId))
                .isEqualTo("QUEUED");

        sendSignedWebhook("del-check-done", "check_run", Map.of(
                "action", "completed",
                "repository", Map.of("id", f.githubRepoId),
                "check_run", Map.of(
                        "id", 55001L,
                        "name", "unit-tests",
                        "head_sha", SHA_CURRENT,
                        "status", "completed",
                        "conclusion", "success",
                        "started_at", "2026-11-24T11:00:00Z",
                        "completed_at", "2026-11-24T11:02:00Z",
                        "pull_requests", List.of(Map.of("number", 55))
                )
        ));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_runs WHERE repository_id = ? AND github_run_id = 55001", Integer.class, f.repositoryId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT conclusion FROM ci_runs WHERE repository_id = ? AND github_run_id = 55001", String.class, f.repositoryId))
                .isEqualTo("SUCCESS");

        // 3. workflow_run webhook -> upserts ci_runs
        sendSignedWebhook("del-workflow-done", "workflow_run", Map.of(
                "action", "completed",
                "repository", Map.of("id", f.githubRepoId),
                "workflow_run", Map.of(
                        "id", 66001L,
                        "run_attempt", 2,
                        "name", "CI Pipeline",
                        "head_sha", SHA_CURRENT,
                        "head_branch", "feature/" + f.itemKey,
                        "status", "completed",
                        "conclusion", "failure",
                        "run_started_at", "2026-11-24T11:05:00Z",
                        "updated_at", "2026-11-24T11:08:00Z",
                        "pull_requests", List.of(Map.of("number", 55))
                )
        ));
        Map<String, Object> wfRow = jdbc.queryForMap(
                "SELECT run_attempt, status, conclusion, work_item_id FROM ci_runs WHERE repository_id = ? AND github_run_id = 66001", f.repositoryId);
        assertThat(wfRow.get("run_attempt")).isEqualTo(2);
        assertThat(wfRow.get("status")).isEqualTo("COMPLETED");
        assertThat(wfRow.get("conclusion")).isEqualTo("FAILURE");
        assertThat(wfRow.get("work_item_id")).isEqualTo(f.workItemId);

        // 4. pull_request_review: self-approval rejected
        sendSignedWebhook("del-rev-self", "pull_request_review", Map.of(
                "action", "submitted",
                "repository", Map.of("id", f.githubRepoId),
                "pull_request", Map.of("number", 55, "user", Map.of("login", "dev-author"), "head", Map.of("sha", SHA_CURRENT)),
                "review", Map.of("id", 81001L, "state", "approved", "commit_id", SHA_CURRENT, "body", "self", "user", Map.of("login", "dev-author"))
        ));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM code_reviews WHERE repository_id = ?", Integer.class, f.repositoryId))
                .isEqualTo(0);

        // 5. pull_request_review: head SHA mismatch rejected
        sendSignedWebhook("del-rev-stale", "pull_request_review", Map.of(
                "action", "submitted",
                "repository", Map.of("id", f.githubRepoId),
                "pull_request", Map.of("number", 55, "user", Map.of("login", "dev-author"), "head", Map.of("sha", SHA_CURRENT)),
                "review", Map.of("id", 81002L, "state", "approved", "commit_id", SHA_STALE, "body", "stale", "user", Map.of("login", "dev-peer"))
        ));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM code_reviews WHERE repository_id = ?", Integer.class, f.repositoryId))
                .isEqualTo(0);

        // 6. pull_request_review: viewer without DEVELOPER role rejected
        sendSignedWebhook("del-rev-viewer", "pull_request_review", Map.of(
                "action", "submitted",
                "repository", Map.of("id", f.githubRepoId),
                "pull_request", Map.of("number", 55, "user", Map.of("login", "dev-author"), "head", Map.of("sha", SHA_CURRENT)),
                "review", Map.of("id", 81003L, "state", "approved", "commit_id", SHA_CURRENT, "body", "viewer", "user", Map.of("login", "viewer-gh"))
        ));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM code_reviews WHERE repository_id = ?", Integer.class, f.repositoryId))
                .isEqualTo(0);

        // 7. pull_request_review: valid changes_requested -> REQUIRES_CHANGES, then approved -> PASS
        sendSignedWebhook("del-rev-changes", "pull_request_review", Map.of(
                "action", "submitted",
                "repository", Map.of("id", f.githubRepoId),
                "pull_request", Map.of("number", 55, "user", Map.of("login", "dev-author"), "head", Map.of("sha", SHA_CURRENT)),
                "review", Map.of("id", 81004L, "state", "changes_requested", "commit_id", SHA_CURRENT, "body", "Add null check", "user", Map.of("login", "dev-peer"))
        ));
        sendSignedWebhook("del-rev-pass", "pull_request_review", Map.of(
                "action", "submitted",
                "repository", Map.of("id", f.githubRepoId),
                "pull_request", Map.of("number", 55, "user", Map.of("login", "dev-author"), "head", Map.of("sha", SHA_CURRENT)),
                "review", Map.of("id", 81005L, "state", "approved", "commit_id", SHA_CURRENT, "body", "LGTM!", "user", Map.of("login", "dev-peer"))
        ));
        List<String> reviewStatuses = jdbc.queryForList(
                "SELECT status FROM code_reviews WHERE repository_id = ? ORDER BY created_at ASC", String.class, f.repositoryId);
        assertThat(reviewStatuses).containsExactly("REQUIRES_CHANGES", "PASS");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM github_activities WHERE repository_id = ? AND kind = 'HUMAN_REVIEW'", Integer.class, f.repositoryId))
                .isEqualTo(2);

        // 8. Part H Invariant: PR merge MUST NOT mark work item DONE!
        sendSignedWebhook("del-pr-merged", "pull_request", Map.of(
                "action", "closed",
                "repository", Map.of("id", f.githubRepoId),
                "pull_request", Map.of(
                        "number", 55,
                        "state", "closed",
                        "merged", true,
                        "merged_at", "2026-11-24T11:30:00Z",
                        "title", f.itemKey + " Webhook PR",
                        "user", Map.of("login", "dev-author"),
                        "head", Map.of("sha", SHA_CURRENT, "ref", "feature/" + f.itemKey)
                )
        ));
        assertThat(jdbc.queryForObject(
                "SELECT state FROM github_activities WHERE repository_id = ? AND kind = 'PULL_REQUEST' AND pr_number = 55",
                String.class, f.repositoryId)).isEqualTo("MERGED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM work_items WHERE id = ?", String.class, f.workItemId))
                .isNotEqualTo("DONE")
                .isEqualTo("IN_REVIEW");
    }

    @Test
    @DisplayName("Rate-limit / 5xx from GitHub API degrades non-fatally to HTTP 503 GITHUB_API_UNAVAILABLE and non-fatal backfill")
    void apiFailure_nonFatal503AndBackfillDegradation() throws Exception {
        Fixture f = createFixture();
        MOCK_API_FORCE_STATUS.set(429);

        String syncUrl = "/api/v1/workspaces/" + f.workspaceId + "/projects/" + f.projectId
                + "/github/repositories/" + f.repositoryId + "/sync";

        mvc.perform(post(syncUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.pmToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GITHUB_API_UNAVAILABLE"));

        // Direct backfill invocation outside DB tx must degrade cleanly without throwing
        ClaimedJob job = new ClaimedJob(
                UUID.randomUUID(),
                BackgroundJob.JobKind.GITHUB_BACKFILL,
                f.workspaceId,
                f.projectId,
                null,
                "backfill-" + f.repositoryId,
                Map.of("repositoryId", f.repositoryId.toString()),
                1
        );
        JobResult result = backfillJobHandler.handle(job);
        assertThat(result.details().get("outcome")).isEqualTo("BACKFILLED");
        assertThat(result.details().get("remoteSyncStatus")).isEqualTo("DEGRADED");
    }

    @Test
    @DisplayName("PostgreSQL code_reviews trigger rejection rolls back SAVEPOINT cleanly so github_events still transitions to DONE without 25P02")
    void codeReviewTriggerRejection_rollsBackSavepointWithoutPoisoningOuterTx() throws Exception {
        Fixture f = createFixture();
        OffsetDateTime olderTime = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        OffsetDateTime newerTime = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);

        // Older PR #77 activity row attributed to dev-peer, newer PR #77 row attributed to dev-author.
        // Java pre-check inspects latest row (dev-author != dev-peer) and attempts INSERT into code_reviews;
        // PostgreSQL V006 trigger ds_review_evidence scans ALL PULL_REQUEST rows for PR #77 and raises 23514.
        jdbc.update(
                """
                INSERT INTO github_activities (
                    id, project_id, repository_id, work_item_id, kind, github_object_id,
                    pr_number, pr_head_sha, actor_login, state, title, payload,
                    happened_at, synced_at, created_at, updated_at, version
                ) VALUES (
                    gen_random_uuid(), ?, ?, ?, 'PULL_REQUEST', '77-historical',
                    77, ?, 'dev-peer', 'OPEN', 'Historical PR row', '{}'::jsonb,
                    ?, now(), now(), now(), 0
                )
                """,
                f.projectId, f.repositoryId, f.workItemId, SHA_CURRENT, olderTime
        );
        jdbc.update(
                """
                INSERT INTO github_activities (
                    id, project_id, repository_id, work_item_id, kind, github_object_id,
                    pr_number, pr_head_sha, actor_login, state, title, payload,
                    happened_at, synced_at, created_at, updated_at, version
                ) VALUES (
                    gen_random_uuid(), ?, ?, ?, 'PULL_REQUEST', '77',
                    77, ?, 'dev-author', 'OPEN', 'Current PR row', '{}'::jsonb,
                    ?, now(), now(), now(), 0
                )
                """,
                f.projectId, f.repositoryId, f.workItemId, SHA_CURRENT, newerTime
        );

        String deliveryId = "del-trigger-savepoint-77";
        sendSignedWebhook(deliveryId, "pull_request_review", Map.of(
                "action", "submitted",
                "repository", Map.of("id", f.githubRepoId),
                "pull_request", Map.of(
                        "number", 77,
                        "user", Map.of("login", "dev-author"),
                        "head", Map.of("sha", SHA_CURRENT)
                ),
                "review", Map.of(
                        "id", 97701L,
                        "state", "approved",
                        "commit_id", SHA_CURRENT,
                        "body", "Triggers ds_review_evidence 23514",
                        "user", Map.of("login", "dev-peer")
                )
        ));

        // Verify SAVEPOINT rollback prevented 25P02 and outer transaction completed cleanly
        Map<String, Object> eventRow = jdbc.queryForMap(
                "SELECT process_status, processed_at FROM github_events WHERE delivery_id = ?", deliveryId);
        assertThat(eventRow.get("process_status")).isEqualTo("DONE");
        assertThat(eventRow.get("processed_at")).isNotNull();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM code_reviews WHERE repository_id = ? AND pr_number = 77",
                Integer.class, f.repositoryId)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM github_activities WHERE repository_id = ? AND pr_number = 77 AND kind = 'HUMAN_REVIEW'",
                Integer.class, f.repositoryId)).isEqualTo(0);
    }

    private void sendSignedWebhook(String deliveryId, String eventType, Map<String, Object> payload) throws Exception {
        byte[] body = json.writeValueAsBytes(payload);
        String signature = computeHmac(body, WEBHOOK_SECRET);
        mvc.perform(post("/api/v1/github/webhook")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", eventType)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    private static String computeHmac(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private Fixture createFixture() {
        UUID pmId = createUser("pm-live@example.test", "PM Live");
        UUID devAuthorId = createUser("dev-author@example.test", "Dev Author");
        UUID devPeerId = createUser("dev-peer@example.test", "Dev Peer");
        UUID viewerId = createUser("viewer-live@example.test", "Viewer Live");

        linkGitHubOAuth(devAuthorId, "gh-1001", "dev-author");
        linkGitHubOAuth(devPeerId, "gh-1002", "dev-peer");
        linkGitHubOAuth(viewerId, "gh-1003", "viewer-gh");

        UUID workspaceId = createWorkspace(pmId, "ws-gh-live", "WS GitHub Live");
        addWorkspaceMember(workspaceId, devAuthorId, "MEMBER");
        addWorkspaceMember(workspaceId, devPeerId, "MEMBER");
        addWorkspaceMember(workspaceId, viewerId, "MEMBER");

        UUID projectId = createProject(workspaceId, "LIVE", "Live Sync Project", pmId);
        addProjectMemberWithRole(projectId, workspaceId, devAuthorId, "DEVELOPER", pmId);
        addProjectMemberWithRole(projectId, workspaceId, devPeerId, "DEVELOPER", pmId);
        addProjectMemberWithRole(projectId, workspaceId, viewerId, "VIEWER", pmId);

        UUID workItemId = createWorkItemInReview(projectId, "LIVE-1", devAuthorId);
        long githubRepoId = 99887766L;
        UUID repositoryId = insertRepository(workspaceId, projectId, 12345L, githubRepoId, "devsphere-org/live-repo");

        return new Fixture(
                workspaceId,
                projectId,
                repositoryId,
                githubRepoId,
                workItemId,
                "LIVE-1",
                pmId,
                devAuthorId,
                devPeerId,
                issueAccessToken(pmId),
                issueAccessToken(devPeerId)
        );
    }

    private UUID createUser(String email, String displayName) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO users (id, email, display_name, password_hash, system_role, status, email_verified_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 'argon2_dummy_hash', 'USER', 'ACTIVE', ?, ?, ?, 0)",
                id, email, displayName, now, now, now
        );
        return id;
    }

    private void linkGitHubOAuth(UUID userId, String providerSubject, String login) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO oauth_accounts (id, user_id, provider, provider_subject, provider_login, linked_at, created_at) " +
                        "VALUES (gen_random_uuid(), ?, 'GITHUB', ?, ?, ?, ?)",
                userId, providerSubject, login, now, now
        );
    }

    private String issueAccessToken(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        byte[] refreshHashBytes = new byte[32];
        random.nextBytes(refreshHashBytes);
        jdbc.update(
                "INSERT INTO auth_sessions (id, user_id, refresh_hash, issued_at, expires_at, session_family_id, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                sessionId, userId, Base64.getEncoder().encodeToString(refreshHashBytes), now, now.plusDays(7), familyId, now
        );
        return sessionTokens.issueAccess(userId, sessionId).value();
    }

    private UUID createWorkspace(UUID ownerId, String slug, String name) {
        return tx.execute(status -> {
            UUID workspaceId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO workspaces (id, slug, name, owner_id, status, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)",
                    workspaceId, slug, name, ownerId, now, now
            );
            jdbc.update(
                    "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'OWNER', 'ACTIVE', ?, ?, ?, 0)",
                    workspaceId, ownerId, now, now, now
            );
            return workspaceId;
        });
    }

    private void addWorkspaceMember(UUID workspaceId, UUID userId, String role) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                workspaceId, userId, role, now, now, now
        );
    }

    private UUID createProject(UUID workspaceId, String key, String name, UUID pmUserId) {
        return tx.execute(status -> {
            UUID projectId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO projects (id, workspace_id, project_key, name, description, objective, status, timezone, planned_start, sprint_length_days, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'Desc', 'Obj', 'PLANNING', 'UTC', '2026-12-01', 14, ?, ?, 0)",
                    projectId, workspaceId, key, name, now, now
            );
            UUID memberId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                    memberId, projectId, workspaceId, pmUserId, now, now, now
            );
            jdbc.update(
                    "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                            "VALUES (?, ?, ?, 'PM', ?, ?, ?, ?)",
                    UUID.randomUUID(), projectId, memberId, pmUserId, now, pmUserId, now
            );
            return projectId;
        });
    }

    private void addProjectMemberWithRole(UUID projectId, UUID workspaceId, UUID userId, String role, UUID grantedBy) {
        tx.executeWithoutResult(status -> {
            UUID memberId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                    memberId, projectId, workspaceId, userId, now, now, now
            );
            jdbc.update(
                    "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), projectId, memberId, role, grantedBy, now, userId, now
            );
        });
    }

    private UUID createWorkItemInReview(UUID projectId, String itemKey, UUID assigneeId) {
        return tx.execute(status -> {
            UUID id = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO work_items (id, project_id, item_key, type, title, priority, status, assignee_id, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 'TASK', 'Live sync task', 'HIGH', 'TO_DO', ?, ?, ?, 0)",
                    id, projectId, itemKey, assigneeId, now, now
            );
            jdbc.update("UPDATE work_items SET status = 'IN_PROGRESS', updated_at = now() WHERE id = ?", id);
            jdbc.update("UPDATE work_items SET status = 'IN_REVIEW', updated_at = now() WHERE id = ?", id);
            return id;
        });
    }

    private UUID insertRepository(UUID workspaceId, UUID projectId, long installationId, long githubRepoId, String fullName) {
        UUID repoId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO github_repositories (id, workspace_id, project_id, github_installation_id, github_repo_id, full_name, default_branch, private, active, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'main', true, true, ?, ?, 0)",
                repoId, workspaceId, projectId, installationId, githubRepoId, fullName, now, now
        );
        return repoId;
    }

    private record Fixture(
            UUID workspaceId,
            UUID projectId,
            UUID repositoryId,
            long githubRepoId,
            UUID workItemId,
            String itemKey,
            UUID pmId,
            UUID devAuthorId,
            UUID devPeerId,
            String pmToken,
            String devPeerToken
    ) {
    }
}
