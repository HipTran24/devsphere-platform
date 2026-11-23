package com.project.devsphere.operations.application.handlers;

import com.project.devsphere.monitoring.application.AlertService;
import com.project.devsphere.monitoring.domain.Alert;
import com.project.devsphere.notification.application.NotificationService;
import com.project.devsphere.notification.domain.Notification;
import com.project.devsphere.operations.application.ClaimedJob;
import com.project.devsphere.operations.application.JobHandler;
import com.project.devsphere.operations.application.JobResult;
import com.project.devsphere.operations.application.NonRetryableJobException;
import com.project.devsphere.operations.domain.BackgroundJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes {@code OUTBOX_EVENT} jobs to in-process consumers.
 *
 * <p>{@code WORK_ITEM_OVERDUE} events re-verify that the item is still overdue, raise (or refresh)
 * a deduplicated monitoring alert and notify the assignee with an e-mail request. Other event
 * types, such as project command events written by {@code ProjectTransactions}, have no in-process
 * subscriber yet and are acknowledged so the outbox drains.</p>
 */
@Component
public class OutboxEventJobHandler implements JobHandler {

    public static final String WORK_ITEM_OVERDUE = "WORK_ITEM_OVERDUE";

    private static final Logger log = LoggerFactory.getLogger(OutboxEventJobHandler.class);

    private final JdbcTemplate jdbc;
    private final ObjectProvider<NotificationService> notificationService;
    private final ObjectProvider<AlertService> alertService;

    public OutboxEventJobHandler(
            JdbcTemplate jdbc,
            ObjectProvider<NotificationService> notificationService,
            ObjectProvider<AlertService> alertService) {
        this.jdbc = jdbc;
        this.notificationService = notificationService;
        this.alertService = alertService;
    }

    @Override
    public BackgroundJob.JobKind kind() {
        return BackgroundJob.JobKind.OUTBOX_EVENT;
    }

    @Override
    public JobResult handle(ClaimedJob job) {
        String eventType = job.requireString("eventType");
        if (WORK_ITEM_OVERDUE.equals(eventType)) {
            return handleOverdue(job);
        }
        log.debug("Acknowledged outbox event {} for job {}", eventType, job.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventType", eventType);
        details.put("outcome", "ACKNOWLEDGED");
        return JobResult.of(details);
    }

    private JobResult handleOverdue(ClaimedJob job) {
        UUID workItemId = job.requireUuid("aggregateId");
        if (job.projectId() == null || job.workspaceId() == null) {
            throw new NonRetryableJobException("Overdue events must be project scoped");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select w.id, w.item_key, w.title, w.assignee_id, w.due_at
                from work_items w
                where w.id=? and w.project_id=? and w.status<>'DONE' and w.deleted_at is null
                  and w.due_at is not null and w.due_at<now()
                """, workItemId, job.projectId());
        if (rows.isEmpty()) {
            return JobResult.skipped("WORK_ITEM_NO_LONGER_OVERDUE");
        }
        Map<String, Object> item = rows.getFirst();
        String itemKey = String.valueOf(item.get("item_key"));
        String title = String.valueOf(item.get("title"));
        Timestamp dueAt = (Timestamp) item.get("due_at");
        UUID assigneeId = (UUID) item.get("assignee_id");

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("source", "overdue-scan");
        evidence.put("itemKey", itemKey);
        evidence.put("dueAt", dueAt.toInstant().toString());
        Alert alert = alertService.getObject().createOrUpdateAlert(
                job.projectId(), workItemId, WORK_ITEM_OVERDUE, Alert.Severity.MEDIUM,
                truncate("Work item " + itemKey + " is overdue", 255), evidence, null);

        boolean notified = false;
        if (assigneeId != null) {
            String day = LocalDate.now(ZoneOffset.UTC).toString();
            notificationService.getObject().createNotification(
                    job.workspaceId(), assigneeId, job.projectId(),
                    WORK_ITEM_OVERDUE,
                    truncate("work-item-overdue:" + workItemId + ":" + day, 180),
                    truncate(itemKey + " is overdue", 180),
                    truncate("\"" + title + "\" passed its due date " + dueAt.toInstant() + ".", 2000),
                    "/projects/" + job.projectId() + "/work-items/" + workItemId,
                    Notification.EmailStatus.PENDING);
            notified = true;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventType", WORK_ITEM_OVERDUE);
        details.put("outcome", "PROCESSED");
        details.put("alertId", alert.getId() == null ? null : alert.getId().toString());
        details.put("assigneeNotified", notified);
        return JobResult.of(details);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
