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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Backfills GitHub activity for one connected repository by syncing recorded deliveries that were
 * never processed (for example because the instance restarted between receipt and sync).
 * Each run handles at most {@link #MAX_EVENTS_PER_RUN} events, oldest first.
 */
@Component
public class GitHubBackfillJobHandler implements JobHandler {

    public static final int MAX_EVENTS_PER_RUN = 200;

    private final JdbcTemplate jdbc;
    private final GitHubEventRepository eventRepository;
    private final GitHubActivitySyncService activitySyncService;

    public GitHubBackfillJobHandler(
            JdbcTemplate jdbc,
            GitHubEventRepository eventRepository,
            GitHubActivitySyncService activitySyncService) {
        this.jdbc = jdbc;
        this.eventRepository = eventRepository;
        this.activitySyncService = activitySyncService;
    }

    @Override
    public BackgroundJob.JobKind kind() {
        return BackgroundJob.JobKind.GITHUB_BACKFILL;
    }

    @Override
    public JobResult handle(ClaimedJob job) {
        UUID repositoryId = job.requireUuid("repositoryId");
        if (job.projectId() == null) {
            throw new NonRetryableJobException("GitHub backfill must be project scoped");
        }
        Boolean active = jdbc.query(
                "select active from github_repositories where id=? and project_id=?",
                rs -> rs.next() ? rs.getBoolean(1) : null, repositoryId, job.projectId());
        if (active == null) {
            throw new NonRetryableJobException("Repository is not connected to the project");
        }
        if (!active) {
            return JobResult.skipped("REPOSITORY_INACTIVE");
        }

        List<UUID> pending = jdbc.queryForList("""
                select id from github_events
                where repository_id=? and project_id=? and process_status='RECEIVED'
                  and received_at<now()-interval '1 minute'
                order by received_at, id limit ?
                """, UUID.class, repositoryId, job.projectId(), MAX_EVENTS_PER_RUN);

        int synced = 0;
        for (UUID eventId : pending) {
            Optional<GitHubEvent> event = eventRepository.findByIdAndProjectId(eventId, job.projectId());
            if (event.isPresent() && event.get().getProcessStatus() == GitHubEvent.ProcessStatus.RECEIVED) {
                activitySyncService.syncEvent(event.get());
                synced++;
            }
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("repositoryId", repositoryId.toString());
        details.put("eventsSynced", synced);
        details.put("outcome", "BACKFILLED");
        return JobResult.of(details);
    }
}
