package com.project.devsphere;

import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.operations.infrastructure.quartz.AbstractOperationsJob;
import com.project.devsphere.operations.infrastructure.quartz.DailyReportScheduleJob;
import com.project.devsphere.operations.infrastructure.quartz.GitHubBackfillSyncJob;
import com.project.devsphere.operations.infrastructure.quartz.NightlyBackupCheckJob;
import com.project.devsphere.operations.infrastructure.quartz.OutboxAndEmailDispatchJob;
import com.project.devsphere.operations.infrastructure.quartz.OverdueWorkItemScanJob;
import com.project.devsphere.operations.infrastructure.quartz.QuartzJobManagementService;
import com.project.devsphere.operations.infrastructure.quartz.QuartzSchedulerConfig;
import com.project.devsphere.operations.infrastructure.quartz.WebhookRetryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the real Quartz scheduler (auto-startup enabled) with cron schedules pushed far into the
 * future, then fires jobs manually so the test fully controls execution. The context is discarded
 * afterwards so no started scheduler leaks into other test classes.
 */
@SpringBootTest(properties = {
        "spring.quartz.auto-startup=true",
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.initial-delay-ms=600000",
        "devsphere.quartz.cron.overdue-scan=0 0 0 1 1 ? 2099",
        "devsphere.quartz.cron.webhook-retry=0 0 0 1 1 ? 2099",
        "devsphere.quartz.cron.outbox-dispatch=0 0 0 1 1 ? 2099",
        "devsphere.quartz.cron.github-backfill=0 0 0 1 1 ? 2099",
        "devsphere.quartz.cron.daily-report=0 0 0 1 1 ? 2099",
        "devsphere.quartz.cron.nightly-backup=0 0 0 1 1 ? 2099"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuartzSchedulerIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired private Scheduler scheduler;
    @Autowired private QuartzJobManagementService management;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private JavaMailSender mailSender;

    private SessionService.Principal admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
    }

    @BeforeEach
    void cleanDatabase() {
        var tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname='public' and tablename<>'flyway_schema_history'", String.class);
        jdbc.execute("truncate table " + String.join(",", tables) + " cascade");
        UUID adminId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("insert into users(id,email,display_name,system_role,status,email_verified_at) values (?,?,?,'ADMIN','ACTIVE',?)",
                adminId, adminId + "@example.test", "Scheduler Admin", now);
        admin = new SessionService.Principal(adminId, UUID.randomUUID(), "Scheduler Admin", "ADMIN");
    }

    @Test
    void schedulerStartsWithAllSixDurableJobsAndCronTriggers() throws Exception {
        assertThat(scheduler.isStarted()).isTrue();
        assertThat(scheduler.isInStandbyMode()).isFalse();
        assertThat(management.status().jobCount()).isEqualTo(6);

        for (String jobName : QuartzSchedulerConfig.JOB_NAMES) {
            JobDetail detail = scheduler.getJobDetail(QuartzSchedulerConfig.jobKey(jobName));
            assertThat(detail).as(jobName).isNotNull();
            assertThat(detail.isDurable()).isTrue();
            assertThat(detail.isConcurrentExecutionDisallowed()).as("%s disallows concurrency", jobName).isTrue();
            List<? extends Trigger> triggers = scheduler.getTriggersOfJob(detail.getKey());
            assertThat(triggers).singleElement().isInstanceOf(CronTrigger.class);
            CronTrigger cron = (CronTrigger) triggers.getFirst();
            assertThat(cron.getCronExpression()).isEqualTo("0 0 0 1 1 ? 2099");
            assertThat(cron.getTimeZone().getID()).isEqualTo("UTC");
            assertThat(cron.getMisfireInstruction()).isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
        }
    }

    @Test
    void everyJobClassIsAnnotatedAgainstConcurrentExecution() {
        for (Class<? extends AbstractOperationsJob> type : List.of(OverdueWorkItemScanJob.class, WebhookRetryJob.class,
                OutboxAndEmailDispatchJob.class, GitHubBackfillSyncJob.class, DailyReportScheduleJob.class, NightlyBackupCheckJob.class)) {
            assertThat(type.isAnnotationPresent(DisallowConcurrentExecution.class)).as(type.getSimpleName()).isTrue();
        }
    }

    @Test
    void manualTriggerRunsNightlyBackupCheckThroughTheDurableQueue() throws Exception {
        management.triggerJob(NightlyBackupCheckJob.NAME, admin);

        awaitTrue(() -> "SUCCESS".equals(single("select status from background_jobs where job_kind='BACKUP_CHECK'")));
        assertThat(single("select result->>'outcome' from background_jobs where job_kind='BACKUP_CHECK'")).isEqualTo("HEALTHY");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='QUARTZ_JOB_TRIGGERED' and actor_id=?",
                Integer.class, admin.userId())).isEqualTo(1);
        assertThat(scheduler.getTriggersOfJob(QuartzSchedulerConfig.jobKey(NightlyBackupCheckJob.NAME)).getFirst().getNextFireTime())
                .isAfter(java.util.Date.from(Instant.parse("2098-12-31T00:00:00Z")));
    }

    @Test
    void manualTriggerOfOutboxDispatchDrainsQueuedEvents() {
        UUID job = UUID.randomUUID();
        jdbc.update("insert into background_jobs(id,job_kind,idempotency_key,payload) values (?,'OUTBOX_EVENT','quartz-it','{\"schemaVersion\":1,\"eventType\":\"SPRINT_STARTED\"}'::jsonb)", job);

        management.triggerJob(OutboxAndEmailDispatchJob.NAME, admin);

        awaitTrue(() -> "SUCCESS".equals(jdbc.queryForObject("select status from background_jobs where id=?", String.class, job)));
    }

    @Test
    void unknownJobsCannotBeTriggered() {
        assertThatThrownBy(() -> management.triggerJob("notAJob", admin)).hasMessageContaining("Unknown scheduled job");
        assertThatThrownBy(() -> management.triggerJob("../../etc", admin)).hasMessageContaining("Unknown scheduled job");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='QUARTZ_JOB_TRIGGERED'", Integer.class)).isZero();
    }

    private String single(String sql) {
        List<String> values = jdbc.queryForList(sql, String.class);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static void awaitTrue(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("Condition not met within " + TIMEOUT);
    }
}
