package com.project.devsphere.operations.application.handlers;

import com.project.devsphere.gitintegration.application.GitHubActivitySyncService;
import com.project.devsphere.gitintegration.domain.GitHubEvent;
import com.project.devsphere.gitintegration.infrastructure.persistence.GitHubEventRepository;
import com.project.devsphere.operations.application.ClaimedJob;
import com.project.devsphere.operations.application.JobHandler;
import com.project.devsphere.operations.application.JobResult;
import com.project.devsphere.operations.application.NonRetryableJobException;
import com.project.devsphere.operations.domain.BackgroundJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Re-processes a recorded GitHub webhook delivery that previously failed or stalled.
 *
 * <p>The event is re-read inside the job transaction; already processed deliveries are skipped,
 * otherwise the attempt counter is bumped and the activity sync is re-run, which marks the event
 * DONE. Any failure rolls the transaction back and the job is retried with backoff.</p>
 */
@Component
public class WebhookRetryJobHandler implements JobHandler {

    public static final int MAX_EVENT_ATTEMPTS = 5;

    private final GitHubEventRepository eventRepository;
    private final GitHubActivitySyncService activitySyncService;
    private final JdbcTemplate jdbc;

    public WebhookRetryJobHandler(
            GitHubEventRepository eventRepository,
            GitHubActivitySyncService activitySyncService,
            JdbcTemplate jdbc) {
        this.eventRepository = eventRepository;
        this.activitySyncService = activitySyncService;
        this.jdbc = jdbc;
    }

    @Override
    public BackgroundJob.JobKind kind() {
        return BackgroundJob.JobKind.WEBHOOK_RETRY;
    }

    @Override
    public JobResult handle(ClaimedJob job) {
        UUID eventId = job.requireUuid("githubEventId");
        if (job.projectId() == null) {
            throw new NonRetryableJobException("Webhook retries must be project scoped");
        }
        GitHubEvent event = eventRepository.findByIdAndProjectId(eventId, job.projectId())
                .orElseThrow(() -> new NonRetryableJobException("GitHub event not found in project"));
        if (event.getProcessStatus() == GitHubEvent.ProcessStatus.DONE) {
            return JobResult.skipped("EVENT_ALREADY_PROCESSED");
        }
        if (event.getAttempts() != null && event.getAttempts() >= MAX_EVENT_ATTEMPTS) {
            throw new NonRetryableJobException("GitHub event exhausted its retry budget");
        }

        jdbc.update("""
                update github_events set process_status='PROCESSING', attempts=attempts+1, updated_at=now()
                where id=? and project_id=? and process_status<>'DONE'
                """, eventId, job.projectId());
        activitySyncService.syncEvent(event);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("githubEventId", eventId.toString());
        details.put("eventType", event.getEventType());
        details.put("outcome", "REPROCESSED");
        return JobResult.of(details);
    }
}
