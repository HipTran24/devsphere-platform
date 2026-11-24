package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.operations.application.OutboxDispatcherService;
import com.project.devsphere.operations.domain.BackgroundJob;
import com.project.devsphere.operations.infrastructure.database.RuntimeRolePrivileges;
import com.project.devsphere.operations.infrastructure.metrics.OperationsMetrics;
import com.project.devsphere.resource.api.ResourceCapacitySnapshot;
import com.project.devsphere.resource.api.SkillLevel;
import com.project.devsphere.resource.api.WeeklySlot;
import com.project.devsphere.solver.application.SchedulingSolver;
import com.project.devsphere.solver.domain.SolverInputSnapshot;
import com.project.devsphere.solver.domain.SolverResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Release gate for Milestone 6 operations: solver and dispatcher performance with metric emission,
 * database-enforced audit immutability for the runtime role, and tenant/admin isolation of the
 * operations surface.
 */
@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
class OperationsPerformanceAndSecurityTest {

    private static final Duration SOLVER_BUDGET = Duration.ofSeconds(30);
    private static final Duration DISPATCH_BUDGET = Duration.ofSeconds(30);

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionTokens sessionTokens;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private OutboxDispatcherService dispatcher;
    @Autowired @Qualifier("cpSatSchedulingSolver") private SchedulingSolver cpSat;

    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
    }

    @BeforeEach
    void cleanDatabase() {
        var tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname='public' and tablename<>'flyway_schema_history'", String.class);
        jdbc.execute("truncate table " + String.join(",", tables) + " cascade");
    }

    // --- performance -------------------------------------------------------------------------

    @Test
    void cpSatSchedulesMultiDeveloperMultiTaskPlanWithinBudgetAndEmitsSolverMetric() {
        LocalDate start = LocalDate.of(2026, 12, 1);
        List<ResourceCapacitySnapshot.DeveloperCapacityRecord> developers = new ArrayList<>();
        Set<UUID> developerIds = new java.util.HashSet<>();
        String[] skills = {"java", "java", "react", "react", "sql"};
        for (String skill : skills) {
            UUID id = UUID.randomUUID();
            developerIds.add(id);
            developers.add(developer(id, skill, start));
        }
        List<SolverInputSnapshot.TaskItem> tasks = new ArrayList<>();
        List<SolverInputSnapshot.TaskDependency> dependencies = new ArrayList<>();
        String[] taskSkills = {"java", "react", "sql"};
        for (int i = 0; i < 30; i++) {
            String skill = taskSkills[i % taskSkills.length];
            tasks.add(new SolverInputSnapshot.TaskItem(UUID.randomUUID(), "PERF-" + (i + 1), "Task " + (i + 1),
                    60 + (i % 4) * 60, List.of(new SolverInputSnapshot.SkillRequirement(skill, 2)), null, null, false));
            if (i >= 3 && i % 3 == 0) {
                dependencies.add(new SolverInputSnapshot.TaskDependency(tasks.get(i - 3).taskId(), tasks.get(i).taskId(), 0));
            }
        }
        SolverInputSnapshot snapshot = new SolverInputSnapshot(UUID.randomUUID(), UUID.randomUUID(), 1L, "UTC",
                start, start.plusDays(60), 20, 4, 42, tasks, dependencies, developers, List.of());
        long before = solverRuns();

        long started = System.nanoTime();
        SolverResult result = cpSat.solve(snapshot);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result.status()).isIn(SolverResult.Status.OPTIMAL, SolverResult.Status.FEASIBLE);
        assertThat(elapsed).isLessThan(SOLVER_BUDGET);
        assertThat(result.assignments()).hasSize(tasks.size());
        assertThat(result.assignments()).allSatisfy(a -> assertThat(developerIds).contains(a.developerId()));
        Map<UUID, SolverResult.TaskAssignment> byTask = result.assignments().stream()
                .collect(Collectors.toMap(SolverResult.TaskAssignment::taskId, a -> a));
        for (SolverInputSnapshot.TaskDependency dependency : dependencies) {
            assertThat(byTask.get(dependency.successorId()).startAt())
                    .isAfterOrEqualTo(byTask.get(dependency.predecessorId()).finishAt());
        }
        assertThat(result.assignments().stream().map(SolverResult.TaskAssignment::developerId).distinct().count())
                .as("work is spread across developers").isGreaterThan(1);
        assertThat(solverRuns()).isEqualTo(before + 1);
    }

    @Test
    void concurrentDispatchersDrainOutboxOnceWithinBudget() throws Exception {
        int jobs = 120;
        for (int i = 0; i < jobs; i++) {
            jdbc.update("insert into background_jobs(job_kind,idempotency_key,payload) values ('OUTBOX_EVENT',?,'{\"schemaVersion\":1,\"eventType\":\"PERF_EVENT\"}'::jsonb)",
                    "perf-" + i);
        }
        double before = outboxSuccesses();

        long started = System.nanoTime();
        List<OutboxDispatcherService.DispatchSummary> summaries = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<OutboxDispatcherService.DispatchSummary>> workers = List.of(
                    () -> dispatcher.dispatchKind(BackgroundJob.JobKind.OUTBOX_EVENT, 100),
                    () -> dispatcher.dispatchKind(BackgroundJob.JobKind.OUTBOX_EVENT, 100));
            for (Future<OutboxDispatcherService.DispatchSummary> future : pool.invokeAll(workers, 60, TimeUnit.SECONDS)) {
                summaries.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(summaries.stream().mapToInt(OutboxDispatcherService.DispatchSummary::claimed).sum()).isEqualTo(jobs);
        assertThat(summaries.stream().mapToInt(OutboxDispatcherService.DispatchSummary::succeeded).sum()).isEqualTo(jobs);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where job_kind='OUTBOX_EVENT' and status='SUCCESS' and attempts=1",
                Integer.class)).as("each job processed exactly once").isEqualTo(jobs);
        assertThat(elapsed).isLessThan(DISPATCH_BUDGET);
        assertThat(outboxSuccesses() - before).isEqualTo(jobs);
    }

    // --- audit immutability ------------------------------------------------------------------

    @Test
    void auditLogsAreAppendOnlyForEveryRoleAndUnmodifiableByTheRuntimeRole() throws Exception {
        UUID auditId = UUID.randomUUID();
        jdbc.update("insert into audit_logs(id,action,entity_type) values (?, 'IMMUTABILITY_PROBE', 'test')", auditId);

        // Trigger-level guard applies even to the owner/superuser connection.
        assertThat(sqlState(() -> jdbc.update("update audit_logs set action='TAMPERED' where id=?", auditId))).isEqualTo("23514");
        assertThat(sqlState(() -> jdbc.update("delete from audit_logs where id=?", auditId))).isEqualTo("23514");

        try (Connection owner = DriverManager.getConnection(DevsphereApplicationTests.POSTGRES.getJdbcUrl(),
                DevsphereApplicationTests.POSTGRES.getUsername(), DevsphereApplicationTests.POSTGRES.getPassword())) {
            new RuntimeRolePrivileges("devsphere_app").apply(owner);
        }
        try (Connection app = DriverManager.getConnection(DevsphereApplicationTests.POSTGRES.getJdbcUrl(),
                "devsphere_app", DevsphereApplicationTests.RUNTIME_PASSWORD);
             var statement = app.createStatement()) {
            assertThat(statement.executeUpdate("insert into audit_logs(action,entity_type) values ('RUNTIME_APPEND','test')")).isEqualTo(1);
            assertThat(catchThrowableOfType(SQLException.class,
                    () -> statement.executeUpdate("update audit_logs set action='TAMPERED' where id='" + auditId + "'")).getSQLState())
                    .isEqualTo("42501");
            assertThat(catchThrowableOfType(SQLException.class,
                    () -> statement.executeUpdate("delete from audit_logs where id='" + auditId + "'")).getSQLState())
                    .isEqualTo("42501");
            assertThat(catchThrowableOfType(SQLException.class,
                    () -> statement.executeUpdate("truncate audit_logs")).getSQLState())
                    .isEqualTo("42501");
        }
        assertThat(jdbc.queryForObject("select action from audit_logs where id=?", String.class, auditId)).isEqualTo("IMMUTABILITY_PROBE");
    }

    // --- tenant and admin isolation ----------------------------------------------------------

    @Test
    void projectManagersCannotReachOperationsAuditOrActuatorSurfaces() throws Exception {
        Tenant a = tenant("AAA");
        Tenant b = tenant("BBB");
        UUID failedInB = UUID.randomUUID();
        jdbc.update("insert into background_jobs(id,workspace_id,project_id,job_kind,status,attempts,idempotency_key) values (?,?,?,'REPORT_EXPORT','FAILED',5,'b-report')",
                failedInB, b.workspace(), b.project());
        String pmToken = token(a.pm());

        mvc.perform(get("/api/v1/admin/operations/jobs").param("projectId", a.project().toString())
                        .header(HttpHeaders.AUTHORIZATION, pmToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/operations/jobs/" + failedInB + "/retry").header(HttpHeaders.AUTHORIZATION, pmToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/operations/jobs/dispatch").header(HttpHeaders.AUTHORIZATION, pmToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/operations/quartz/triggers/nightlyBackupCheck/trigger").header(HttpHeaders.AUTHORIZATION, pmToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit-logs").param("workspaceId", a.workspace().toString()).header(HttpHeaders.AUTHORIZATION, pmToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit-logs/export").param("workspaceId", a.workspace().toString()).header(HttpHeaders.AUTHORIZATION, pmToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, pmToken))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select status from background_jobs where id=?", String.class, failedInB)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action in ('BACKGROUND_JOB_RETRIED','QUARTZ_JOB_TRIGGERED','AUDIT_LOG_EXPORTED')",
                Integer.class)).isZero();
    }

    @Test
    void administratorFiltersNeverLeakAcrossTenants() throws Exception {
        Tenant a = tenant("AAA");
        Tenant b = tenant("BBB");
        for (Tenant t : List.of(a, b)) {
            jdbc.update("insert into background_jobs(workspace_id,project_id,job_kind,idempotency_key) values (?,?,'OUTBOX_EVENT',?)",
                    t.workspace(), t.project(), "evt-" + t.project());
        }
        String adminToken = token(user("ADMIN"));

        JsonNode jobs = json.readTree(mvc.perform(get("/api/v1/admin/operations/jobs").param("projectId", a.project().toString())
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn().getResponse().getContentAsString());
        assertThat(jobs.get("content").get(0).get("projectId").asText()).isEqualTo(a.project().toString());

        mvc.perform(get("/api/v1/admin/operations/jobs").param("workspaceId", b.workspace().toString())
                        .param("projectId", a.project().toString())
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        String csv = mvc.perform(get("/api/v1/admin/audit-logs/export").param("workspaceId", a.workspace().toString())
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains(a.workspace().toString()).doesNotContain(b.workspace().toString())
                .doesNotContain(b.project().toString());
    }

    // --- helpers -----------------------------------------------------------------------------

    private record Tenant(UUID workspace, UUID project, UUID pm) {}

    private Tenant tenant(String key) {
        return tx.execute(s -> {
            UUID pm = user("USER");
            UUID w = UUID.randomUUID();
            UUID p = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            jdbc.update("insert into workspaces(id,slug,name,owner_id) values (?,?,?,?)", w, "w-" + w, "Workspace " + key, pm);
            jdbc.update("insert into workspace_members(workspace_id,user_id,role) values (?,?,'OWNER')", w, pm);
            jdbc.update("insert into projects(id,workspace_id,project_key,name,objective,planned_start) values (?,?,?,?,'Objective',current_date)",
                    p, w, key, "Project " + key);
            jdbc.update("insert into project_members(id,project_id,workspace_id,user_id) values (?,?,?,?)", member, p, w, pm);
            jdbc.update("insert into project_member_roles(project_id,project_member_id,role,granted_by,accepted_by,accepted_at) values (?,?,'PM',?,?,now())",
                    p, member, pm, pm);
            return new Tenant(w, p, pm);
        });
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users(id,email,display_name,password_hash,system_role,status,email_verified_at) values (?,?,?,'argon2_dummy_hash',?,'ACTIVE',now())",
                id, id + "@example.test", role + " user", role);
        return id;
    }

    private String token(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        byte[] refresh = new byte[32];
        random.nextBytes(refresh);
        jdbc.update("insert into auth_sessions(id,user_id,refresh_hash,issued_at,expires_at,session_family_id,created_at) values (?,?,?,?,?,?,?)",
                sessionId, userId, Base64.getEncoder().encodeToString(refresh), now, now.plusDays(7), UUID.randomUUID(), now);
        return "Bearer " + sessionTokens.issueAccess(userId, sessionId).value();
    }

    private static ResourceCapacitySnapshot.DeveloperCapacityRecord developer(UUID id, String skill, LocalDate from) {
        List<WeeklySlot> slots = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            slots.add(new WeeklySlot(day, 540, 1020));
        }
        return new ResourceCapacitySnapshot.DeveloperCapacityRecord(id, true, List.of(new ResourceCapacitySnapshot.CapacityInterval(
                UUID.randomUUID(), 1L, from, null, 480, new BigDecimal("100.00"), 480,
                List.of(new SkillLevel(skill, 3)), slots, List.of())));
    }

    private long solverRuns() {
        Timer timer = meterRegistry.find(OperationsMetrics.SOLVER_DURATION).tag("solver", "cp-sat").timer();
        return timer == null ? 0 : meterRegistry.find(OperationsMetrics.SOLVER_DURATION).tag("solver", "cp-sat").timers()
                .stream().mapToLong(Timer::count).sum();
    }

    private double outboxSuccesses() {
        var counter = meterRegistry.find(OperationsMetrics.OUTBOX_DISPATCHED)
                .tags("kind", "OUTBOX_EVENT", "outcome", "success").counter();
        return counter == null ? 0 : counter.count();
    }

    private static String sqlState(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            Throwable cause = ex;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
            throw ex;
        }
        throw new AssertionError("Statement unexpectedly succeeded");
    }
}
