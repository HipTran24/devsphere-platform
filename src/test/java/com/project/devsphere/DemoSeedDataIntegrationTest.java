package com.project.devsphere;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.operations.api.OperationsAdminController;
import com.project.devsphere.operations.application.DemoSeedDataService;
import com.project.devsphere.operations.application.DemoSeedResultResponse;
import com.project.devsphere.operations.application.DemoSeedRunner;
import com.project.devsphere.operations.application.OperationsAdminService;
import com.project.devsphere.operations.application.OperationsException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "spring.mail.host=localhost",
        "spring.mail.port=2525",
        "devsphere.auth.mail.initial-delay-ms=600000",
        "spring.quartz.auto-startup=false"
})
@AutoConfigureMockMvc
class DemoSeedDataIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private SessionTokens sessionTokens;
    @Autowired private SessionService sessionService;
    @Autowired private DemoSeedDataService demoSeedDataService;
    @Autowired private OperationsAdminService operationsAdminService;
    @Autowired private AccessPolicy accessPolicy;
    @Autowired private ObjectProvider<DemoSeedDataService> demoSeedDataServiceProvider;
    @MockitoBean private JavaMailSender mailSender;

    private final SecureRandom random = new SecureRandom();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        hmac[0] = 41;
        encryption[0] = 42;
        registry.add("devsphere.auth.otp.hmac-key", () -> Base64.getEncoder().encodeToString(hmac));
        registry.add("devsphere.auth.otp.encryption-key", () -> Base64.getEncoder().encodeToString(encryption));
    }

    @BeforeEach
    void cleanDatabase() {
        var tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class
        );
        jdbc.execute("TRUNCATE TABLE " + String.join(",", tables) + " CASCADE");
    }

    private UUID createUser(String email, String displayName, String systemRole) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO users (id, email, display_name, password_hash, system_role, status, email_verified_at, created_at, updated_at, version) "
                        + "VALUES (?, ?::citext, ?, 'argon2_dummy_hash', ?, 'ACTIVE', ?, ?, ?, 0)",
                id, email, displayName, systemRole, now, now, now
        );
        return id;
    }

    private String issueAccessToken(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        byte[] refresh = new byte[32];
        random.nextBytes(refresh);
        jdbc.update(
                "INSERT INTO auth_sessions (id, user_id, refresh_hash, issued_at, expires_at, session_family_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                sessionId, userId, Base64.getEncoder().encodeToString(refresh), now, now.plusDays(7), UUID.randomUUID(), now
        );
        return sessionTokens.issueAccess(userId, sessionId).value();
    }

    @Test
    @DisplayName("Default devsphere.seed.demo.enabled=false disables DemoSeedRunner, blocks /seed-demo with 403 even for Admin, and prod hard-pins false")
    void defaultDisabledBlocksEndpointAndProdHardPinsFalse() throws Exception {
        // 1. DemoSeedRunner is NOT instantiated when devsphere.seed.demo.enabled is default false
        assertThat(applicationContext.getBeansOfType(DemoSeedRunner.class)).isEmpty();

        UUID adminId = createUser("seed-admin@example.test", "Seed Admin", "ADMIN");
        UUID regularId = createUser("seed-user@example.test", "Seed User", "USER");
        String adminToken = issueAccessToken(adminId);
        String userToken = issueAccessToken(regularId);

        // 2. Anonymous -> 401
        mvc.perform(post("/api/v1/admin/operations/seed-demo"))
                .andExpect(status().isUnauthorized());

        // 3. Regular user -> 403
        mvc.perform(post("/api/v1/admin/operations/seed-demo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());

        // 4. System Admin -> 403 DEMO_SEED_DISABLED when disabled by default
        mvc.perform(post("/api/v1/admin/operations/seed-demo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("DEMO_SEED_DISABLED"));

        // 5. Verify application-prod.properties hard-pins devsphere.seed.demo.enabled=false without placeholder
        String prodProps = new String(
                new ClassPathResource("application-prod.properties").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        assertThat(prodProps)
                .contains("devsphere.seed.demo.enabled=false")
                .doesNotContain("DEVSPHERE_SEED_DEMO_ENABLED");

        // 6. Defense-in-depth: OperationsAdminController blocks when prod profile is active even if flag=true
        MockEnvironment prodEnv = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        prodEnv.setActiveProfiles("prod");
        OperationsAdminController prodController = new OperationsAdminController(
                operationsAdminService, accessPolicy, demoSeedDataServiceProvider, true, prodEnv
        );
        SessionService.Principal adminPrincipal = sessionService.authenticate(adminToken).orElseThrow();
        assertThatThrownBy(() -> prodController.seedDemo(adminPrincipal))
                .isInstanceOf(OperationsException.class)
                .satisfies(ex -> {
                    OperationsException ops = (OperationsException) ex;
                    assertThat(ops.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ops.getCode()).isEqualTo("DEMO_SEED_DISABLED");
                });

        // 7. Legacy 2-arg constructor still works and defaults to disabled
        OperationsAdminController legacyController = new OperationsAdminController(operationsAdminService, accessPolicy);
        assertThatThrownBy(() -> legacyController.seedDemo(adminPrincipal))
                .isInstanceOf(OperationsException.class)
                .satisfies(ex -> assertThat(((OperationsException) ex).getCode()).isEqualTo("DEMO_SEED_DISABLED"));
    }

    @Test
    @DisplayName("Enabled seed runs idempotently twice with zero duplicates, Argon2id logins succeed, and all 36-table DB constraints hold")
    void enabledSeedIsIdempotentSupportsArgon2LoginAndRespectsAllConstraints() {
        // Seed twice in a row to prove strict idempotency across frozen DONE work items, immutable requirement_versions, roles, etc.
        DemoSeedResultResponse first = demoSeedDataService.seedDemoData();
        DemoSeedResultResponse second = demoSeedDataService.seedDemoData();

        assertThat(first.seeded()).isTrue();
        assertThat(second.seeded()).isTrue();
        assertThat(first.workspaceSlug()).isEqualTo(DemoSeedDataService.WORKSPACE_SLUG);
        assertThat(first.projectKey()).isEqualTo(DemoSeedDataService.PROJECT_KEY);
        assertThat(second.workspaceId()).isEqualTo(first.workspaceId());
        assertThat(second.projectId()).isEqualTo(first.projectId());
        assertThat(second.counts()).isEqualTo(first.counts());

        Map<String, Integer> counts = second.counts();
        assertThat(counts.get("users")).isEqualTo(7);
        assertThat(counts.get("oauthAccounts")).isEqualTo(2);
        assertThat(counts.get("workspaceMembers")).isEqualTo(6);
        assertThat(counts.get("projectMembers")).isEqualTo(6);
        assertThat(counts.get("projectRoles")).isEqualTo(6);
        assertThat(counts.get("requirements")).isEqualTo(3);
        assertThat(counts.get("requirementVersions")).isEqualTo(3);
        assertThat(counts.get("workItems")).isEqualTo(7);
        assertThat(counts.get("dependencies")).isEqualTo(3);
        assertThat(counts.get("sprints")).isEqualTo(1);
        assertThat(counts.get("milestones")).isEqualTo(1);
        assertThat(counts.get("developerCapacities")).isEqualTo(2);
        assertThat(counts.get("planProposals")).isEqualTo(1);
        assertThat(counts.get("githubRepositories")).isEqualTo(1);
        assertThat(counts.get("githubActivities")).isEqualTo(1);
        assertThat(counts.get("ciRuns")).isEqualTo(1);
        assertThat(counts.get("codeReviews")).isEqualTo(1);
        assertThat(counts.get("chatChannels")).isEqualTo(2);
        assertThat(counts.get("chatMessages")).isEqualTo(4);
        assertThat(counts.get("alerts")).isEqualTo(1);
        assertThat(counts.get("reports")).isEqualTo(2);

        // Verify enabled OperationsAdminController returns 200 OK for System Admin on a third idempotent call
        SessionService.Attempt adminLogin = sessionService.login(DemoSeedDataService.ADMIN_EMAIL, demoSeedDataService.resolvedDemoPassphrase());
        assertThat(adminLogin.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);
        SessionService.Principal adminPrincipal = sessionService.authenticate(adminLogin.access().value()).orElseThrow();
        OperationsAdminController enabledController = new OperationsAdminController(
                operationsAdminService, accessPolicy, demoSeedDataServiceProvider, true, new MockEnvironment()
        );
        ResponseEntity<DemoSeedResultResponse> controllerResp = enabledController.seedDemo(adminPrincipal);
        assertThat(controllerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controllerResp.getBody()).isNotNull();
        assertThat(controllerResp.getBody().counts()).isEqualTo(first.counts());

        // Verify ALL 7 seeded users log in via SessionService.login (Argon2id) and reject wrong passwords
        List<String> seededEmails = List.of(
                DemoSeedDataService.ADMIN_EMAIL,
                DemoSeedDataService.OWNER_EMAIL,
                DemoSeedDataService.PM_EMAIL,
                DemoSeedDataService.DEV1_EMAIL,
                DemoSeedDataService.DEV2_EMAIL,
                DemoSeedDataService.CLIENT_EMAIL,
                DemoSeedDataService.VIEWER_EMAIL
        );
        for (String email : seededEmails) {
            SessionService.Attempt ok = sessionService.login(email, demoSeedDataService.resolvedDemoPassphrase());
            assertThat(ok.outcome()).as("Login for %s", email).isEqualTo(SessionService.Outcome.SUCCESS);
            assertThat(sessionService.authenticate(ok.access().value())).isPresent();
        }
        assertThat(sessionService.login(DemoSeedDataService.PM_EMAIL, "wrong-test-passphrase-for-rejection").outcome())
                .isEqualTo(SessionService.Outcome.INVALID_CREDENTIALS);

        // Verify 36-table schema freeze & V006 trigger invariants
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history'",
                Integer.class)).isEqualTo(36);
        assertThat(entityManager.getMetamodel().getEntities()).hasSize(36);

        Map<String, Object> projectRow = jdbc.queryForMap(
                "SELECT status, committed_deadline FROM projects WHERE id = ?",
                DemoSeedDataService.PROJECT_ID
        );
        assertThat(projectRow.get("status")).isEqualTo("ACTIVE");
        assertThat(projectRow.get("committed_deadline")).isNull();

        Map<String, Object> doneTask = jdbc.queryForMap(
                "SELECT status, accepted_by, completed_at FROM work_items WHERE id = ?",
                DemoSeedDataService.TASK_4_DONE_ID
        );
        assertThat(doneTask.get("status")).isEqualTo("DONE");
        assertThat(doneTask.get("accepted_by")).isEqualTo(DemoSeedDataService.PM_USER_ID);
        assertThat(doneTask.get("completed_at")).isNotNull();

        Map<String, Object> reviewRow = jdbc.queryForMap(
                "SELECT reviewer_kind, scope, status, reviewer_id, head_sha FROM code_reviews WHERE project_id = ?",
                DemoSeedDataService.PROJECT_ID
        );
        assertThat(reviewRow.get("reviewer_kind")).isEqualTo("HUMAN");
        assertThat(reviewRow.get("scope")).isEqualTo("PR");
        assertThat(reviewRow.get("status")).isEqualTo("PASS");
        assertThat(reviewRow.get("reviewer_id")).isEqualTo(DemoSeedDataService.DEV2_USER_ID);
        assertThat(((String) reviewRow.get("head_sha")).trim()).isEqualTo(DemoSeedDataService.DEMO_PR_HEAD_SHA);

        // CLIENT must NOT be in TEAM channel, and MUST be in CLIENT_PM channel
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_channel_members WHERE channel_id = ? AND user_id = ?",
                Integer.class, DemoSeedDataService.TEAM_CHANNEL_ID, DemoSeedDataService.CLIENT_USER_ID
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_channel_members WHERE channel_id = ? AND user_id = ?",
                Integer.class, DemoSeedDataService.CLIENT_PM_CHANNEL_ID, DemoSeedDataService.CLIENT_USER_ID
        )).isEqualTo(1);

        // Alerts fingerprint is 64 lowercase hex chars
        String fingerprint = jdbc.queryForObject(
                "SELECT fingerprint FROM alerts WHERE id = ?",
                String.class, DemoSeedDataService.ALERT_ID
        );
        assertThat(fingerprint).matches("^[0-9a-f]{64}$");

        // Published report visible to client + draft report hidden
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reports WHERE project_id = ? AND status = 'PUBLISHED' AND visible_to_client = true AND approved_by IS NOT NULL",
                Integer.class, DemoSeedDataService.PROJECT_ID
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reports WHERE project_id = ? AND status = 'DRAFT' AND visible_to_client = false",
                Integer.class, DemoSeedDataService.PROJECT_ID
        )).isEqualTo(1);
    }
}
