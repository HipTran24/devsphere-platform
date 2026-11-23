package com.project.devsphere.operations.application;

import com.project.devsphere.administration.api.dto.AdminPageResponse;
import com.project.devsphere.administration.application.AuditLogService;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.operations.api.dto.BackgroundJobResponse;
import com.project.devsphere.operations.api.dto.DispatchResultResponse;
import com.project.devsphere.operations.api.dto.JobQueueStatsResponse;
import com.project.devsphere.operations.api.dto.OperationsHealthResponse;
import com.project.devsphere.operations.api.dto.QuartzTriggerResponse;
import com.project.devsphere.operations.domain.BackgroundJob;
import com.project.devsphere.operations.infrastructure.persistence.BackgroundJobRepository;
import com.project.devsphere.operations.infrastructure.persistence.JobQueue;
import com.project.devsphere.operations.infrastructure.quartz.QuartzJobManagementService;
import com.project.devsphere.operations.infrastructure.quartz.QuartzSchedulerConfig;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Use cases behind {@code /api/v1/admin/operations}; callers must already be verified system admins. */
@Service
public class OperationsAdminService {

    private final JobQueue jobQueue;
    private final BackgroundJobRepository jobRepository;
    private final OutboxDispatcherService dispatcher;
    private final QuartzJobManagementService quartzJobs;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbc;

    public OperationsAdminService(
            JobQueue jobQueue,
            BackgroundJobRepository jobRepository,
            OutboxDispatcherService dispatcher,
            QuartzJobManagementService quartzJobs,
            AuditLogService auditLogService,
            JdbcTemplate jdbc) {
        this.jobQueue = jobQueue;
        this.jobRepository = jobRepository;
        this.dispatcher = dispatcher;
        this.quartzJobs = quartzJobs;
        this.auditLogService = auditLogService;
        this.jdbc = jdbc;
    }

    public OperationsHealthResponse health() {
        boolean databaseUp;
        JobQueue.Stats stats = null;
        try {
            Integer one = jdbc.queryForObject("select 1", Integer.class);
            databaseUp = one != null && one == 1;
            stats = jobQueue.stats();
        } catch (DataAccessException ex) {
            databaseUp = false;
        }
        QuartzJobManagementService.SchedulerStatus scheduler = quartzJobs.status();
        boolean schedulerRunning = scheduler.started() && !scheduler.inStandby() && !scheduler.shutdown();
        long queued = stats == null ? 0 : stats.total(BackgroundJob.Status.QUEUED);
        long running = stats == null ? 0 : stats.total(BackgroundJob.Status.RUNNING);
        long failed = stats == null ? 0 : stats.total(BackgroundJob.Status.FAILED);
        long dead = stats == null ? 0 : stats.deadLettered();
        long expired = stats == null ? 0 : stats.expiredLeases();
        String status = !databaseUp ? "DOWN"
                : (expired > 0 || dead > 0 || !schedulerRunning) ? "DEGRADED" : "UP";
        return new OperationsHealthResponse(status, databaseUp, scheduler.started(), scheduler.inStandby(),
                scheduler.jobCount(), queued, running, failed, dead, expired, Instant.now());
    }

    public AdminPageResponse<BackgroundJobResponse> listJobs(
            int page, int size, BackgroundJob.JobKind jobKind, BackgroundJob.Status status, UUID workspaceId, UUID projectId) {
        if (page < 0 || page > 10000) {
            throw OperationsException.validationFailed("Page index out of bounds (0..10000)");
        }
        if (size < 1 || size > 100) {
            throw OperationsException.validationFailed("Page size out of bounds (1..100)");
        }
        if (jobKind != null && JobQueue.NON_DISPATCHABLE_KINDS.contains(jobKind)) {
            throw OperationsException.validationFailed("Job kind is not visible to operators");
        }
        Page<BackgroundJob> jobs = jobRepository.searchForAdmin(jobKind, status, workspaceId, projectId,
                JobQueue.NON_DISPATCHABLE_KINDS,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        List<BackgroundJobResponse> items = jobs.getContent().stream()
                .map(job -> BackgroundJobResponse.from(job, JobQueue.MAX_ATTEMPTS))
                .toList();
        return new AdminPageResponse<>(items, page, size, jobs.getTotalElements(), jobs.getTotalPages(), null, null);
    }

    public BackgroundJobResponse retryJob(UUID jobId, SessionService.Principal actor) {
        BackgroundJob job = jobRepository.findById(jobId)
                .filter(found -> !JobQueue.NON_DISPATCHABLE_KINDS.contains(found.getJobKind()))
                .orElseThrow(() -> OperationsException.notFound("Background job not found"));
        if (job.getStatus() != BackgroundJob.Status.FAILED) {
            throw OperationsException.stateTransitionInvalid("Only FAILED jobs can be retried");
        }
        if (!jobQueue.retryFailed(jobId)) {
            throw OperationsException.stateTransitionInvalid("Job changed state concurrently; reload and retry");
        }
        auditLogService.record(actor.userId(), "BACKGROUND_JOB_RETRIED", "background_job", jobId,
                Map.of("jobKind", job.getJobKind().name(), "previousAttempts", job.getAttempts()));
        BackgroundJob reloaded = jobRepository.findById(jobId)
                .orElseThrow(() -> OperationsException.notFound("Background job not found"));
        return BackgroundJobResponse.from(reloaded, JobQueue.MAX_ATTEMPTS);
    }

    public DispatchResultResponse dispatch(int maxJobs, BackgroundJob.JobKind jobKind, SessionService.Principal actor) {
        OutboxDispatcherService.DispatchSummary summary = jobKind == null
                ? dispatcher.dispatchBatch(maxJobs)
                : dispatcher.dispatchKind(jobKind, maxJobs);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("maxJobs", maxJobs);
        metadata.put("jobKind", jobKind == null ? "ALL" : jobKind.name());
        metadata.put("claimed", summary.claimed());
        metadata.put("failed", summary.failed());
        auditLogService.record(actor.userId(), "BACKGROUND_JOBS_DISPATCHED", "background_job", null, metadata);
        return new DispatchResultResponse(summary.claimed(), summary.succeeded(), summary.failed(),
                summary.deadLettered(), summary.processedByKind(), summary.durationMillis());
    }

    public JobQueueStatsResponse stats() {
        JobQueue.Stats stats = jobQueue.stats();
        return new JobQueueStatsResponse(stats.countsByKindAndStatus(), stats.totalsByStatus(),
                stats.expiredLeases(), stats.deadLettered(), stats.oldestQueuedAt(), Instant.now());
    }

    public List<QuartzTriggerResponse> triggers() {
        return quartzJobs.listTriggers().stream().map(OperationsAdminService::toResponse).toList();
    }

    public QuartzTriggerResponse triggerJob(String jobName, SessionService.Principal actor) {
        return toResponse(quartzJobs.triggerJob(jobName, actor));
    }

    public static List<String> jobNames() {
        return QuartzSchedulerConfig.JOB_NAMES;
    }

    private static QuartzTriggerResponse toResponse(QuartzJobManagementService.TriggerInfo info) {
        return new QuartzTriggerResponse(info.jobName(), info.triggerName(), info.group(), info.description(),
                info.cronExpression(), info.timeZone(), info.state(), info.nextFireTime(), info.previousFireTime());
    }
}
