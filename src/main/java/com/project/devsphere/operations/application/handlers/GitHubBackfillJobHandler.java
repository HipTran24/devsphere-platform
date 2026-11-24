package com.project.devsphere.operations.application.handlers;

import com.project.devsphere.gitintegration.api.dto.GitHubSyncSummaryResponse;
import com.project.devsphere.gitintegration.application.GitHubActivitySyncService;
import com.project.devsphere.gitintegration.application.GitHubRemoteSyncService;
import com.project.devsphere.gitintegration.domain.GitHubEvent;
import com.project.devsphere.gitintegration.infrastructure.GitHubApiUnavailableException;
import com.project.devsphere.gitintegration.infrastructure.GitHubAppProperties;
import com.project.devsphere.gitintegration.infrastructure.persistence.GitHubEventRepository;
import com.project.devsphere.operations.application.ClaimedJob;
import com.project.devsphere.operations.application.JobHandler;
import com.project.devsphere.operations.application.JobResult;
import com.project.devsphere.operations.application.NonRetryableJobException;
import com.project.devsphere.operations.domain.BackgroundJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Backfills GitHub activity for one connected repository by syncing recorded deliveries that were
 * never processed (for example because the instance restarted between receipt and sync).
 * Each run handles at most {@link #MAX_EVENTS_PER_RUN} events, oldest first.
 *
 * <p>When {@code devsphere.github.app.enabled=true} and {@link GitHubRemoteSyncService} is available,
 * also performs an optional remote REST API sync strictly outside any active DB transaction
 * (degrading non-fatally on {@link GitHubApiUnavailableException}).
 */
@Component
public class GitHubBackfillJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(GitHubBackfillJobHandler.class);
    public static final int MAX_EVENTS_PER_RUN = 200;

    private final JdbcTemplate jdbc;
    private final GitHubEventRepository eventRepository;
    private final GitHubActivitySyncService activitySyncService;
    private final ObjectProvider<GitHubRemoteSyncService> remoteSyncServiceProvider;
    private final ObjectProvider<GitHubAppProperties> githubAppPropertiesProvider;

    @Autowired
    public GitHubBackfillJobHandler(
            JdbcTemplate jdbc,
            GitHubEventRepository eventRepository,
            GitHubActivitySyncService activitySyncService,
            ObjectProvider<GitHubRemoteSyncService> remoteSyncServiceProvider,
            ObjectProvider<GitHubAppProperties> githubAppPropertiesProvider) {
        this.jdbc = jdbc;
        this.eventRepository = eventRepository;
        this.activitySyncService = activitySyncService;
        this.remoteSyncServiceProvider = remoteSyncServiceProvider;
        this.githubAppPropertiesProvider = githubAppPropertiesProvider;
    }

    public GitHubBackfillJobHandler(
            JdbcTemplate jdbc,
            GitHubEventRepository eventRepository,
            GitHubActivitySyncService activitySyncService) {
        this(jdbc, eventRepository, activitySyncService, null, null);
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

        triggerOptionalRemoteBackfill(job.projectId(), repositoryId, details);

        return JobResult.of(details);
    }

    private void triggerOptionalRemoteBackfill(UUID projectId, UUID repositoryId, Map<String, Object> details) {
        GitHubAppProperties properties = githubAppPropertiesProvider != null
                ? githubAppPropertiesProvider.getIfAvailable()
                : null;
        GitHubRemoteSyncService remoteSyncService = remoteSyncServiceProvider != null
                ? remoteSyncServiceProvider.getIfAvailable()
                : null;
        if (properties == null || !properties.isEnabled() || remoteSyncService == null) {
            return;
        }

        // Never perform GitHub HTTP I/O inside an active DB transaction.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runRemoteBackfillNonFatal(remoteSyncService, projectId, repositoryId, null);
                }
            });
            details.put("remoteSyncStatus", "SCHEDULED_AFTER_COMMIT");
        } else {
            runRemoteBackfillNonFatal(remoteSyncService, projectId, repositoryId, details);
        }
    }

    private void runRemoteBackfillNonFatal(
            GitHubRemoteSyncService remoteSyncService,
            UUID projectId,
            UUID repositoryId,
            Map<String, Object> details) {
        try {
            GitHubSyncSummaryResponse summary = remoteSyncService.syncRepositoryForBackfill(projectId, repositoryId);
            if (details != null && summary != null) {
                details.put("remoteSyncStatus", "SYNCED");
                details.put("remotePrsSynced", summary.prsSynced());
                details.put("remoteCommitsSynced", summary.commitsSynced());
                details.put("remoteCiRunsSynced", summary.ciRunsSynced());
                details.put("remoteReviewsSynced", summary.reviewsSynced());
            }
        } catch (GitHubApiUnavailableException ex) {
            log.warn("GitHub remote backfill degraded for repositoryId={}: {}", repositoryId, ex.getMessage());
            if (details != null) {
                details.put("remoteSyncStatus", "DEGRADED");
                details.put("remoteSyncReason", ex.getMessage());
            }
        }
    }
}
