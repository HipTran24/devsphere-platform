package com.project.devsphere;

import com.project.devsphere.administration.application.AuditLogService;
import com.project.devsphere.gitintegration.application.GitHubWebhookService;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.operations.application.OperationsScanService;
import com.project.devsphere.operations.application.OutboxDispatcherService;
import com.project.devsphere.operations.domain.BackgroundJob;
import com.project.devsphere.operations.infrastructure.metrics.OperationsMetrics;
import com.project.devsphere.operations.infrastructure.quartz.NightlyBackupCheckJob;
import com.project.devsphere.solver.application.SchedulingSolver;
import com.project.devsphere.solver.domain.SolverInputSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.initial-delay-ms=600000",
        "devsphere.github.webhook-secret=actuator-test-secret"
})
@AutoConfigureMockMvc
class ActuatorAndPrometheusIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionTokens sessionTokens;
    @Autowired private ApplicationContext context;
    @Autowired private Scheduler scheduler;
    @Autowired private AuditLogService auditLogService;
    @Autowired private OperationsScanService scanService;
    @Autowired private OutboxDispatcherService dispatcher;
    @Autowired private GitHubWebhookService webhookService;
    @Autowired private List<SchedulingSolver> solvers;

    private final SecureRandom random = new SecureRandom();
    private UUID adminId;
    private String adminToken;
    private String userToken;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
    }

    @BeforeEach
    void setUp() {
        var tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname='public' and tablename<>'flyway_schema_history'", String.class);
        jdbc.execute("truncate table " + String.join(",", tables) + " cascade");
        adminId = createUser("ADMIN");
        adminToken = issueAccessToken(adminId);
        userToken = issueAccessToken(createUser("USER"));
    }

    private UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("insert into users(id,email,display_name,password_hash,system_role,status,email_verified_at) values (?,?,?,'argon2_dummy_hash',?,'ACTIVE',?)",
                id, id + "@example.test", role + " user", role, now);
        return id;
    }

    private String issueAccessToken(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        byte[] refresh = new byte[32];
        random.nextBytes(refresh);
        jdbc.update("insert into auth_sessions(id,user_id,refresh_hash,issued_at,expires_at,session_family_id,created_at) values (?,?,?,?,?,?,?)",
                sessionId, userId, Base64.getEncoder().encodeToString(refresh), now, now.plusDays(7), UUID.randomUUID(), now);
        return "Bearer " + sessionTokens.issueAccess(userId, sessionId).value();
    }

    @Test
    void healthProbesArePublicButHideComponentDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void administratorsSeeCustomHealthIndicators() throws Exception {
        mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.jobQueue.status").value("UP"))
                .andExpect(jsonPath("$.components.jobQueue.details.degraded").value(false))
                .andExpect(jsonPath("$.components.storage.status").value("UP"))
                .andExpect(jsonPath("$.components.storage.details.mode").value("local"))
                .andExpect(jsonPath("$.components.quartz.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.components.quartz.details.jobs").value(6))
                .andExpect(jsonPath("$.components.quartz.details.standby").value(true));
        mvc.perform(get("/actuator/health/readiness").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
                .andExpect(jsonPath("$.components.jobQueue").exists())
                .andExpect(jsonPath("$.components.quartz").doesNotExist());
    }

    @Test
    void nonHealthActuatorEndpointsRequireAdministrators() throws Exception {
        for (String path : new String[]{"/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/jvm.memory.used", "/actuator/info"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, userToken)).andExpect(status().isForbidden());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer forged.token.value")).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/actuator/info").header(HttpHeaders.AUTHORIZATION, adminToken)).andExpect(status().isOk());
        mvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, adminToken)).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/heapdump").header(HttpHeaders.AUTHORIZATION, adminToken)).andExpect(status().isNotFound());
    }

    @Test
    void prometheusScrapeExposesEveryOperationsMetric() throws Exception {
        // Generate one sample for every operations meter through the real code paths.
        auditLogService.record(adminId, "METRICS_PROBE", "actuator_test", null, Map.of());
        assertThat(scanService.enqueueBackupCheck()).isEqualTo(1);
        dispatcher.dispatchKind(BackgroundJob.JobKind.BACKUP_CHECK, 1);
        assertThatThrownBy(() -> webhookService.verifySignature("{}".getBytes(StandardCharsets.UTF_8), "sha256=00", null))
                .isNotNull();
        SolverInputSnapshot empty = new SolverInputSnapshot(UUID.randomUUID(), UUID.randomUUID(), 1L, "UTC",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), 5, 1, 1, List.of(), List.of(), List.of(), List.of());
        solvers.forEach(solver -> solver.solve(empty));
        runNightlyBackupJob();

        String scrape = mvc.perform(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape).contains(
                OperationsMetrics.PROMETHEUS_QUARTZ_JOB_EXECUTIONS + "{",
                OperationsMetrics.PROMETHEUS_OUTBOX_DISPATCHED + "{",
                OperationsMetrics.PROMETHEUS_WEBHOOK_FAILURES + "{",
                OperationsMetrics.PROMETHEUS_AUDIT_EVENTS + "{",
                OperationsMetrics.PROMETHEUS_QUARTZ_JOB_DURATION + "_count{",
                OperationsMetrics.PROMETHEUS_SOLVER_DURATION + "_count{",
                OperationsMetrics.PROMETHEUS_JOBS_QUEUED + "{",
                OperationsMetrics.PROMETHEUS_JOBS_RUNNING + "{",
                OperationsMetrics.PROMETHEUS_JOBS_FAILED + "{",
                OperationsMetrics.PROMETHEUS_BACKUP_LAST_SUCCESS + "{");
        assertThat(scrape).contains("job=\"nightlyBackupCheck\"", "outcome=\"success\"", "kind=\"BACKUP_CHECK\"",
                "reason=\"invalid_signature\"", "solver=\"cp-sat\"", "application=\"devsphere\"");
        assertThat(scrape).doesNotContain(OperationsMetrics.PROMETHEUS_BACKUP_LAST_SUCCESS + "{application=\"devsphere\"} 0.0");

        mvc.perform(get("/actuator/metrics/" + OperationsMetrics.JOBS_QUEUED).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(OperationsMetrics.JOBS_QUEUED));
    }

    private void runNightlyBackupJob() throws Exception {
        NightlyBackupCheckJob job = context.getAutowireCapableBeanFactory().createBean(NightlyBackupCheckJob.class);
        JobExecutionContext execution = mock(JobExecutionContext.class);
        when(execution.getScheduler()).thenReturn(scheduler);
        when(execution.getMergedJobDataMap()).thenReturn(new JobDataMap());
        job.execute(execution);
    }
}
