package com.project.devsphere.operations.application;

import com.project.devsphere.operations.domain.BackgroundJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detects due work and turns it into idempotent {@code background_jobs} entries. Every scan is
 * bounded and keys its jobs by a time bucket, so overlapping or repeated runs never duplicate work.
 */
@Service
public class OperationsScanService {

    public static final int MAX_SCAN_ROWS = 500;

    private static final Logger log = LoggerFactory.getLogger(OperationsScanService.class);
    private static final DateTimeFormatter HALF_HOUR_BUCKET = DateTimeFormatter.ofPattern("yyyyMMdd'T'HH").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final OutboxService outboxService;
    private final Clock clock;

    @Autowired
    public OperationsScanService(JdbcTemplate jdbc, OutboxService outboxService) {
        this(jdbc, outboxService, Clock.systemUTC());
    }

    OperationsScanService(JdbcTemplate jdbc, OutboxService outboxService, Clock clock) {
        this.jdbc = jdbc;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    /** Enqueues one WORK_ITEM_OVERDUE outbox event per overdue open work item per UTC day. */
    public int enqueueOverdueWorkItems() {
        String day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select w.id, w.project_id, p.workspace_id, w.item_key, w.assignee_id, w.due_at
                from work_items w join projects p on p.id=w.project_id
                where w.due_at is not null and w.due_at<now() and w.status<>'DONE' and w.deleted_at is null
                  and p.status in ('PLANNING','ACTIVE')
                order by w.due_at, w.id limit ?
                """, MAX_SCAN_ROWS);
        int created = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("itemKey", row.get("item_key"));
            data.put("assigneeId", row.get("assignee_id") == null ? null : row.get("assignee_id").toString());
            data.put("dueAt", ((Timestamp) row.get("due_at")).toInstant().toString());
            created += count(outboxService.enqueueOutboxEvent(
                    (UUID) row.get("workspace_id"), (UUID) row.get("project_id"), null,
                    "WORK_ITEM_OVERDUE", "work_item", (UUID) row.get("id"), day, data));
        }
        return created;
    }

    /** Enqueues WEBHOOK_RETRY jobs for failed or stalled GitHub deliveries that still have retry budget. */
    public int enqueueWebhookRetries() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select e.id, e.project_id, p.workspace_id, e.attempts
                from github_events e join projects p on p.id=e.project_id
                where e.attempts<5 and (e.process_status='FAILED'
                   or (e.process_status in ('RECEIVED','PROCESSING') and e.received_at<now()-interval '10 minutes'))
                order by e.received_at, e.id limit ?
                """, MAX_SCAN_ROWS);
        int created = 0;
        for (Map<String, Object> row : rows) {
            UUID eventId = (UUID) row.get("id");
            created += count(outboxService.enqueueJob(new OutboxService.EnqueueRequest(
                    BackgroundJob.JobKind.WEBHOOK_RETRY,
                    (UUID) row.get("workspace_id"), (UUID) row.get("project_id"), null,
                    OutboxService.idempotencyKey("webhook-retry", eventId.toString(), String.valueOf(row.get("attempts"))),
                    Map.of("githubEventId", eventId.toString()),
                    null)));
        }
        return created;
    }

    /** Enqueues one GITHUB_BACKFILL job per active repository per half-hour bucket. */
    public int enqueueGithubBackfills() {
        String bucket = HALF_HOUR_BUCKET.format(clock.instant()) + (clock.instant().atZone(ZoneOffset.UTC).getMinute() < 30 ? "00" : "30");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.id, r.project_id, p.workspace_id
                from github_repositories r join projects p on p.id=r.project_id
                where r.active and p.status in ('PLANNING','ACTIVE')
                order by r.id limit ?
                """, MAX_SCAN_ROWS);
        int created = 0;
        for (Map<String, Object> row : rows) {
            UUID repositoryId = (UUID) row.get("id");
            created += count(outboxService.enqueueJob(new OutboxService.EnqueueRequest(
                    BackgroundJob.JobKind.GITHUB_BACKFILL,
                    (UUID) row.get("workspace_id"), (UUID) row.get("project_id"), null,
                    OutboxService.idempotencyKey("github-backfill", repositoryId.toString(), bucket),
                    Map.of("repositoryId", repositoryId.toString()),
                    null)));
        }
        return created;
    }

    /** Enqueues one DAILY REPORT_EXPORT job per active project for the previous UTC day. */
    public int enqueueDailyReports() {
        String period = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1).toString();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, workspace_id from projects where status='ACTIVE' order by id limit ?", MAX_SCAN_ROWS);
        int created = 0;
        for (Map<String, Object> row : rows) {
            UUID projectId = (UUID) row.get("id");
            created += count(outboxService.enqueueJob(new OutboxService.EnqueueRequest(
                    BackgroundJob.JobKind.REPORT_EXPORT,
                    (UUID) row.get("workspace_id"), projectId, null,
                    OutboxService.idempotencyKey("daily-report", projectId.toString(), period),
                    Map.of("reportType", "DAILY", "periodDate", period),
                    null)));
        }
        return created;
    }

    /** Enqueues the system-wide BACKUP_CHECK job for the current UTC day. */
    public int enqueueBackupCheck() {
        String day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        return count(outboxService.enqueueJob(new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.BACKUP_CHECK, null, null, null,
                OutboxService.idempotencyKey("backup-check", day),
                Map.of("checkDate", day),
                null)));
    }

    private static int count(OutboxService.EnqueueResult result) {
        if (result.created()) {
            log.debug("Enqueued background job {} ({})", result.jobId(), result.idempotencyKey());
            return 1;
        }
        return 0;
    }
}
