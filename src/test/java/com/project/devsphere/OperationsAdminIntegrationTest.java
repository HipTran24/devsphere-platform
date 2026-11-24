package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.notification.application.NotificationService;
import com.project.devsphere.notification.domain.Notification;
import com.project.devsphere.operations.application.OperationsScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "spring.mail.host=localhost",
        "spring.mail.port=2525",
        "devsphere.auth.mail.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
class OperationsAdminIntegrationTest {

    private static final String OPS = "/api/v1/admin/operations";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionTokens sessionTokens;
    @Autowired private NotificationService notificationService;
    @Autowired private OperationsScanService scanService;
    @MockitoBean private JavaMailSender mailSender;

    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    private UUID adminId;
    private String adminToken;
    private String userToken;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        hmac[0] = 3;
        encryption[0] = 4;
        registry.add("devsphere.auth.otp.hmac-key", () -> Base64.getEncoder().encodeToString(hmac));
        registry.add("devsphere.auth.otp.encryption-key", () -> Base64.getEncoder().encodeToString(encryption));
    }

    @BeforeEach
    void cleanDatabase() {
        var tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class);
        jdbc.execute("TRUNCATE TABLE " + String.join(",", tables) + " CASCADE");
        adminId = createUser("ops-admin@example.test", "Ops Admin", "ADMIN");
        adminToken = issueAccessToken(adminId);
        userToken = issueAccessToken(createUser("ops-user@example.test", "Ops User", "USER"));
    }

    // --- helpers -----------------------------------------------------------------------------

    private UUID createUser(String email, String displayName, String systemRole) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO users (id, email, display_name, password_hash, system_role, status, email_verified_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 'argon2_dummy_hash', ?, 'ACTIVE', ?, ?, ?, 0)",
                id, email, displayName, systemRole, now, now, now);
        return id;
    }

    private String issueAccessToken(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        byte[] refresh = new byte[32];
        random.nextBytes(refresh);
        jdbc.update("INSERT INTO auth_sessions (id, user_id, refresh_hash, issued_at, expires_at, session_family_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                sessionId, userId, Base64.getEncoder().encodeToString(refresh), now, now.plusDays(7), UUID.randomUUID(), now);
        return sessionTokens.issueAccess(userId, sessionId).value();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private UUID insertJob(String kind, String status, int attempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into background_jobs(id,job_kind,status,attempts,idempotency_key,last_error) values (?,?,?,?,?,?)",
                id, kind, status, attempts, "test:" + id, "FAILED".equals(status) ? "boom" : null);
        return id;
    }

    private record Project(UUID workspace, UUID project, UUID assignee) {}

    private Project createProject() {
        return tx.execute(s -> {
            UUID owner = createUser(UUID.randomUUID() + "@example.test", "Owner", "USER");
            UUID member = createUser(UUID.randomUUID() + "@example.test", "Member", "USER");
            UUID w = UUID.randomUUID();
            UUID p = UUID.randomUUID();
            jdbc.update("insert into workspaces(id,slug,name,owner_id) values (?,?,?,?)", w, "w-" + w, "Workspace", owner);
            jdbc.update("insert into workspace_members(workspace_id,user_id,role) values (?,?,'OWNER'),(?,?,'MEMBER')", w, owner, w, member);
            jdbc.update("insert into projects(id,workspace_id,project_key,name,objective,planned_start) values (?,?,'OPS','Ops Project','Operate',current_date)", p, w);
            for (UUID u : new UUID[]{owner, member}) {
                UUID m = UUID.randomUUID();
                jdbc.update("insert into project_members(id,project_id,workspace_id,user_id) values (?,?,?,?)", m, p, w, u);
                jdbc.update("insert into project_member_roles(project_id,project_member_id,role,granted_by) values (?,?,'DEVELOPER',?)", p, m, owner);
                if (u.equals(owner)) {
                    jdbc.update("insert into project_member_roles(project_id,project_member_id,role,granted_by,accepted_by,accepted_at) values (?,?,'PM',?,?,now())", p, m, owner, owner);
                }
            }
            return new Project(w, p, member);
        });
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    // --- access control ----------------------------------------------------------------------

    @Nested
    @DisplayName("Operations API access control")
    class AccessControl {

        @Test
        void anonymousCallersAreRejected() throws Exception {
            mvc.perform(get(OPS + "/health")).andExpect(status().isUnauthorized());
            mvc.perform(post(OPS + "/jobs/dispatch")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/audit-logs/export")).andExpect(status().isUnauthorized());
        }

        @Test
        void nonAdminsAreForbiddenEverywhere() throws Exception {
            for (String path : new String[]{"/health", "/jobs", "/outbox/stats", "/quartz/triggers"}) {
                mvc.perform(get(OPS + path).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                        .andExpect(status().isForbidden())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
            }
            mvc.perform(post(OPS + "/quartz/triggers/nightlyBackupCheck/trigger").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/audit-logs/summary").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/audit-logs/export").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void lockedAdministratorIsRejectedByAccessPolicy() throws Exception {
            jdbc.update("update users set status='LOCKED' where id=?", adminId);
            mvc.perform(get(OPS + "/health").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().is4xxClientError());
        }
    }

    // --- health, listing, stats ----------------------------------------------------------------

    @Nested
    @DisplayName("Queue inspection")
    class QueueInspection {

        @Test
        void healthReportsDatabaseSchedulerAndQueueState() throws Exception {
            insertJob("BACKUP_CHECK", "FAILED", 5);
            mvc.perform(get(OPS + "/health").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(header().exists("X-Trace-Id"))
                    .andExpect(jsonPath("$.databaseUp").value(true))
                    .andExpect(jsonPath("$.scheduledJobs").value(6))
                    .andExpect(jsonPath("$.schedulerStarted").value(false))
                    .andExpect(jsonPath("$.deadLetteredJobs").value(1))
                    .andExpect(jsonPath("$.status").value("DEGRADED"));
        }

        @Test
        void jobListingFiltersAndHidesAuthAndIdempotencyRecords() throws Exception {
            UUID failed = insertJob("BACKUP_CHECK", "FAILED", 2);
            insertJob("OUTBOX_EVENT", "QUEUED", 0);
            jdbc.update("insert into background_jobs(job_kind,payload) values ('AUTH_VERIFY_EMAIL', '{\"schemaVersion\":1,\"ciphertext\":\"c\"}'::jsonb)");
            jdbc.update("insert into background_jobs(job_kind) values ('IDEMPOTENCY')");

            MvcResult all = mvc.perform(get(OPS + "/jobs").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andReturn();
            assertThat(all.getResponse().getContentAsString()).doesNotContain("ciphertext").doesNotContain("payload");

            mvc.perform(get(OPS + "/jobs").param("status", "FAILED").param("jobKind", "BACKUP_CHECK")
                            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(failed.toString()))
                    .andExpect(jsonPath("$.content[0].lastError").value("boom"))
                    .andExpect(jsonPath("$.content[0].maxAttempts").value(5));

            mvc.perform(get(OPS + "/jobs").param("jobKind", "AUTH_VERIFY_EMAIL").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            mvc.perform(get(OPS + "/jobs").param("size", "101").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(OPS + "/jobs").param("status", "NOPE").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }

        @Test
        void outboxStatsAggregateByKindAndStatus() throws Exception {
            insertJob("OUTBOX_EVENT", "QUEUED", 0);
            insertJob("OUTBOX_EVENT", "QUEUED", 0);
            insertJob("WEBHOOK_RETRY", "FAILED", 5);
            jdbc.update("insert into background_jobs(job_kind,status,attempts,locked_by,locked_until,started_at) values ('BACKUP_CHECK','RUNNING',1,'gone',now()-interval '1 minute',now())");

            mvc.perform(get(OPS + "/outbox/stats").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.countsByKindAndStatus.OUTBOX_EVENT.QUEUED").value(2))
                    .andExpect(jsonPath("$.countsByKindAndStatus.WEBHOOK_RETRY.FAILED").value(1))
                    .andExpect(jsonPath("$.totalsByStatus.RUNNING").value(1))
                    .andExpect(jsonPath("$.expiredLeases").value(1))
                    .andExpect(jsonPath("$.deadLettered").value(1))
                    .andExpect(jsonPath("$.oldestQueuedAt").exists());
        }
    }

    // --- retry and dispatch ------------------------------------------------------------------

    @Nested
    @DisplayName("Retry and dispatch")
    class RetryAndDispatch {

        @Test
        void failedJobIsRequeuedOnceAndAuditedWithTraceId() throws Exception {
            UUID job = insertJob("BACKUP_CHECK", "FAILED", 5);

            mvc.perform(post(OPS + "/jobs/" + job + "/retry")
                            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                            .header("X-Trace-Id", "ops-retry-trace"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Trace-Id", "ops-retry-trace"))
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andExpect(jsonPath("$.attempts").value(0))
                    .andExpect(jsonPath("$.lastError").doesNotExist());

            assertThat(jdbc.queryForObject(
                    "select count(*) from audit_logs where action='BACKGROUND_JOB_RETRIED' and entity_id=? and actor_id=? and trace_id='ops-retry-trace'",
                    Integer.class, job, adminId)).isEqualTo(1);

            mvc.perform(post(OPS + "/jobs/" + job + "/retry").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STATE_TRANSITION_INVALID"));
        }

        @Test
        void unknownAndHiddenJobsAreNotFound() throws Exception {
            UUID authJob = UUID.randomUUID();
            jdbc.update("insert into background_jobs(id,job_kind,status) values (?,'AUTH_RESET_PASSWORD','FAILED')", authJob);

            mvc.perform(post(OPS + "/jobs/" + UUID.randomUUID() + "/retry").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isNotFound());
            mvc.perform(post(OPS + "/jobs/" + authJob + "/retry").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isNotFound());
            mvc.perform(post(OPS + "/jobs/not-a-uuid/retry").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isBadRequest());
            assertThat(jdbc.queryForObject("select status from background_jobs where id=?", String.class, authJob)).isEqualTo("FAILED");
        }

        @Test
        void dispatchPublishesProjectCommandEventsWithoutCsrfToken() throws Exception {
            UUID job = UUID.randomUUID();
            jdbc.update("insert into background_jobs(id,job_kind,idempotency_key,payload) values (?,'OUTBOX_EVENT','cmd-1','{\"schemaVersion\":1,\"eventType\":\"APPLY_PLAN\",\"result\":{}}'::jsonb)", job);

            mvc.perform(post(OPS + "/jobs/dispatch").param("jobKind", "OUTBOX_EVENT").param("maxJobs", "10")
                            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.claimed").value(1))
                    .andExpect(jsonPath("$.succeeded").value(1))
                    .andExpect(jsonPath("$.processedByKind.OUTBOX_EVENT").value(1));

            assertThat(jdbc.queryForObject("select status from background_jobs where id=?", String.class, job)).isEqualTo("SUCCESS");
            assertThat(jdbc.queryForObject("select result->>'outcome' from background_jobs where id=?", String.class, job)).isEqualTo("ACKNOWLEDGED");
            assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='BACKGROUND_JOBS_DISPATCHED'", Integer.class)).isEqualTo(1);
        }

        @Test
        void dispatchRejectsOutOfRangeBatches() throws Exception {
            mvc.perform(post(OPS + "/jobs/dispatch").param("maxJobs", "0").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            mvc.perform(post(OPS + "/jobs/dispatch").param("maxJobs", "201").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(OPS + "/jobs/dispatch").param("jobKind", "SCHEDULE_SOLVE").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void failingHandlerReschedulesWithBackoffAndKeepsError() throws Exception {
            UUID job = UUID.randomUUID();
            jdbc.update("insert into background_jobs(id,job_kind,idempotency_key,payload) values (?,'REPORT_EXPORT','bad-report','{\"schemaVersion\":1}'::jsonb)", job);

            mvc.perform(post(OPS + "/jobs/dispatch").param("jobKind", "REPORT_EXPORT").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.failed").value(1))
                    .andExpect(jsonPath("$.deadLettered").value(1));

            var row = jdbc.queryForMap("select status, attempts, last_error, locked_by from background_jobs where id=?", job);
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("attempts")).isEqualTo(5);
            assertThat((String) row.get("last_error")).contains("Report generation must be project scoped");
            assertThat(row.get("locked_by")).isNull();
        }
    }

    // --- end-to-end outbox flows -------------------------------------------------------------

    @Nested
    @DisplayName("Outbox flows")
    class OutboxFlows {

        @Test
        void pendingNotificationEmailIsQueuedAndDelivered() throws Exception {
            UUID recipient = createUser("recipient@example.test", "Recipient", "USER");
            Notification notification = tx.execute(s -> notificationService.createNotification(
                    null, recipient, null, "ALERT_RAISED", "alert:1", "Alert raised", "Something needs attention", "/alerts/1",
                    Notification.EmailStatus.PENDING));

            assertThat(jdbc.queryForObject("select count(*) from background_jobs where job_kind='NOTIFICATION_EMAIL' and idempotency_key=?",
                    Integer.class, "notification-email:" + notification.getId())).isEqualTo(1);

            mvc.perform(post(OPS + "/jobs/dispatch").param("jobKind", "NOTIFICATION_EMAIL").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.succeeded").value(1));

            verify(mailSender).send(any(SimpleMailMessage.class));
            assertThat(jdbc.queryForObject("select email_status from notifications where id=?", String.class, notification.getId())).isEqualTo("SENT");
        }

        @Test
        void overdueScanIsIdempotentAndRaisesAlertNotificationAndEmailJob() throws Exception {
            Project project = createProject();
            UUID item = UUID.randomUUID();
            jdbc.update("insert into work_items(id,project_id,type,title,assignee_id,due_at) values (?,?,'TASK','Late task',?,now()-interval '2 days')",
                    item, project.project(), project.assignee());

            assertThat(scanService.enqueueOverdueWorkItems()).isEqualTo(1);
            assertThat(scanService.enqueueOverdueWorkItems()).isZero();

            mvc.perform(post(OPS + "/jobs/dispatch").param("jobKind", "OUTBOX_EVENT").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.succeeded").value(1));

            assertThat(jdbc.queryForObject("select count(*) from alerts where work_item_id=? and alert_code='WORK_ITEM_OVERDUE' and status='NEW'",
                    Integer.class, item)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=? and event_type='WORK_ITEM_OVERDUE' and email_status='PENDING'",
                    Integer.class, project.assignee())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from background_jobs where job_kind='NOTIFICATION_EMAIL' and project_id=?",
                    Integer.class, project.project())).isEqualTo(1);
        }

        @Test
        void dailyReportJobGeneratesDraftOnce() throws Exception {
            Project project = createProject();
            jdbc.update("insert into work_items(project_id,type,title) values (?,'TASK','Backlog item')", project.project());
            jdbc.update("update projects set status='ACTIVE' where id=?", project.project());

            assertThat(scanService.enqueueDailyReports()).isEqualTo(1);
            mvc.perform(post(OPS + "/jobs/dispatch").param("jobKind", "REPORT_EXPORT").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.succeeded").value(1));

            assertThat(jdbc.queryForObject("select count(*) from reports where project_id=? and type='DAILY' and status='DRAFT'",
                    Integer.class, project.project())).isEqualTo(1);
            assertThat(scanService.enqueueDailyReports()).isZero();
        }

        @Test
        void backupCheckRecordsDatabaseEvidence() throws Exception {
            assertThat(scanService.enqueueBackupCheck()).isEqualTo(1);
            mvc.perform(post(OPS + "/jobs/dispatch").param("jobKind", "BACKUP_CHECK").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.succeeded").value(1));
            assertThat(jdbc.queryForObject("select (result->>'tableCount')::int from background_jobs where job_kind='BACKUP_CHECK'", Integer.class))
                    .isGreaterThanOrEqualTo(36);
        }
    }

    // --- quartz ------------------------------------------------------------------------------

    @Nested
    @DisplayName("Quartz triggers")
    class QuartzTriggers {

        @Test
        void listsAllSixCronTriggers() throws Exception {
            MvcResult result = mvc.perform(get(OPS + "/quartz/triggers").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andReturn();
            java.util.Map<String, JsonNode> cron = new java.util.TreeMap<>();
            for (JsonNode trigger : body(result)) {
                if (!trigger.path("cronExpression").isNull() && !trigger.path("cronExpression").isMissingNode()) {
                    cron.put(trigger.get("jobName").asText(), trigger);
                }
            }
            assertThat(cron.keySet()).containsExactlyInAnyOrder(
                    "overdueWorkItemScan", "webhookRetry", "outboxDispatch", "githubBackfillSync", "dailyReportSchedule", "nightlyBackupCheck");
            assertThat(cron.get("nightlyBackupCheck").get("cronExpression").asText()).isEqualTo("0 0 2 * * ?");
            assertThat(cron.get("githubBackfillSync").get("cronExpression").asText()).isEqualTo("0 */30 * * * ?");
            assertThat(cron.get("outboxDispatch").get("triggerName").asText()).isEqualTo("outboxDispatchTrigger");
            assertThat(cron.values()).allSatisfy(trigger -> {
                assertThat(trigger.get("timeZone").asText()).isEqualTo("UTC");
                assertThat(trigger.get("nextFireTime").isNull()).isFalse();
                assertThat(trigger.get("group").asText()).isEqualTo("devsphere-operations");
            });
        }

        @Test
        void manualTriggerIsWhitelistedAndAudited() throws Exception {
            mvc.perform(post(OPS + "/quartz/triggers/nightlyBackupCheck/trigger").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobName").value("nightlyBackupCheck"));
            assertThat(jdbc.queryForObject(
                    "select count(*) from audit_logs where action='QUARTZ_JOB_TRIGGERED' and actor_id=? and metadata->>'jobName'='nightlyBackupCheck'",
                    Integer.class, adminId)).isEqualTo(1);

            mvc.perform(post(OPS + "/quartz/triggers/dropDatabase/trigger").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(jsonPath("$.detail").value("Unknown scheduled job"));
            mvc.perform(post(OPS + "/quartz/triggers/{name}/trigger", "night'ly").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isNotFound());
        }
    }

    // --- audit search, summary and export ----------------------------------------------------

    @Nested
    @DisplayName("Audit log extensions")
    class AuditLogs {

        private void insertAudit(String action, String entityType, String traceId, UUID projectWorkspace) {
            jdbc.update("insert into audit_logs(actor_id,action,entity_type,entity_id,trace_id,workspace_id) values (?,?,?,?,?,?)",
                    adminId, action, entityType, UUID.randomUUID(), traceId, projectWorkspace);
        }

        @Test
        void filtersByTraceIdWorkspaceAndEntity() throws Exception {
            Project project = createProject();
            insertAudit("CUSTOM_ACTION", "thing", "trace-abc", project.workspace());
            insertAudit("CUSTOM_ACTION", "thing", "trace-other", null);
            UUID entity = jdbc.queryForObject("select entity_id from audit_logs where trace_id='trace-abc'", UUID.class);

            mvc.perform(get("/api/v1/admin/audit-logs").param("traceId", "trace-abc").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].traceId").value("trace-abc"));
            mvc.perform(get("/api/v1/admin/audit-logs").param("workspaceId", project.workspace().toString())
                            .param("action", "CUSTOM_ACTION").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
            mvc.perform(get("/api/v1/admin/audit-logs").param("entityId", entity.toString()).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
            mvc.perform(get("/api/v1/admin/audit-logs").param("traceId", "bad trace!").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        void summaryGroupsEventsByAction() throws Exception {
            insertAudit("ALPHA", "thing", null, null);
            insertAudit("ALPHA", "thing", null, null);
            insertAudit("BETA", "thing", null, null);

            mvc.perform(get("/api/v1/admin/audit-logs/summary").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.actions[?(@.action=='ALPHA')].count").value(2))
                    .andExpect(jsonPath("$.actions[?(@.action=='BETA')].count").value(1))
                    .andExpect(jsonPath("$.from").exists())
                    .andExpect(jsonPath("$.to").exists());
            mvc.perform(get("/api/v1/admin/audit-logs/summary").param("from", "2026-01-01T00:00:00Z")
                            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void csvExportNeutralisesFormulaInjection() throws Exception {
            insertAudit("=HYPERLINK(\"http://evil\")", "+cmd", "trace-csv", null);
            insertAudit("@SUM(A1)", "-entity", null, null);

            MvcResult result = mvc.perform(get("/api/v1/admin/audit-logs/export").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.startsWith("attachment")))
                    .andReturn();
            String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(csv).startsWith("id,occurredAt,actorId,action,entityType,entityId,workspaceId,projectId,traceId");
            assertThat(csv).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"").contains(",'+cmd,").contains(",'@SUM(A1),'-entity,");
            assertThat(csv).doesNotContain(",=HYPERLINK").doesNotContain(",@SUM");
            assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='AUDIT_LOG_EXPORTED' and actor_id=?", Integer.class, adminId))
                    .isEqualTo(1);
        }

        @Test
        void jsonExportAndFormatValidation() throws Exception {
            insertAudit("JSON_ACTION", "thing", "trace-json", null);

            mvc.perform(get("/api/v1/admin/audit-logs/export").param("format", "json").param("traceId", "trace-json")
                            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].action").value("JSON_ACTION"));
            mvc.perform(get("/api/v1/admin/audit-logs/export").param("format", "xml").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }
}
