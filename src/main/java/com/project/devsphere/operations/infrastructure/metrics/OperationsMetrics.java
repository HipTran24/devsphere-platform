package com.project.devsphere.operations.infrastructure.metrics;

import com.project.devsphere.operations.domain.BackgroundJob;
import com.project.devsphere.operations.infrastructure.persistence.JobQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Operations telemetry published through Micrometer (and therefore {@code /actuator/prometheus}).
 *
 * <p>Meter names use Micrometer dot notation; the Prometheus registry renders them as the names in
 * the {@code PROMETHEUS_*} constants. Tags are restricted to small, bounded vocabularies.</p>
 */
@Component
public class OperationsMetrics {

    public static final String QUARTZ_JOB_EXECUTIONS = "devsphere.quartz.job.executions";
    public static final String OUTBOX_DISPATCHED = "devsphere.outbox.dispatched";
    public static final String WEBHOOK_FAILURES = "devsphere.webhook.failures";
    public static final String AUDIT_EVENTS = "devsphere.audit.events";
    public static final String QUARTZ_JOB_DURATION = "devsphere.quartz.job.duration";
    public static final String SOLVER_DURATION = "devsphere.solver.duration";
    public static final String JOBS_QUEUED = "devsphere.jobs.queued";
    public static final String JOBS_RUNNING = "devsphere.jobs.running";
    public static final String JOBS_FAILED = "devsphere.jobs.failed";
    public static final String BACKUP_LAST_SUCCESS = "devsphere.backup.last.success.timestamp";

    public static final String PROMETHEUS_QUARTZ_JOB_EXECUTIONS = "devsphere_quartz_job_executions_total";
    public static final String PROMETHEUS_OUTBOX_DISPATCHED = "devsphere_outbox_dispatched_total";
    public static final String PROMETHEUS_WEBHOOK_FAILURES = "devsphere_webhook_failures_total";
    public static final String PROMETHEUS_AUDIT_EVENTS = "devsphere_audit_events_total";
    public static final String PROMETHEUS_QUARTZ_JOB_DURATION = "devsphere_quartz_job_duration_seconds";
    public static final String PROMETHEUS_SOLVER_DURATION = "devsphere_solver_duration_seconds";
    public static final String PROMETHEUS_JOBS_QUEUED = "devsphere_jobs_queued";
    public static final String PROMETHEUS_JOBS_RUNNING = "devsphere_jobs_running";
    public static final String PROMETHEUS_JOBS_FAILED = "devsphere_jobs_failed";
    public static final String PROMETHEUS_BACKUP_LAST_SUCCESS = "devsphere_backup_last_success_timestamp_seconds";

    static final Duration QUEUE_SNAPSHOT_TTL = Duration.ofSeconds(15);

    private final MeterRegistry registry;
    private final ObjectProvider<JobQueue> jobQueue;
    private final Clock clock;
    private final AtomicReference<QueueSnapshot> queueSnapshot = new AtomicReference<>(QueueSnapshot.EMPTY);
    private final AtomicLong backupLastSuccessEpochSeconds = new AtomicLong();

    @Autowired
    public OperationsMetrics(MeterRegistry registry, ObjectProvider<JobQueue> jobQueue) {
        this(registry, jobQueue, Clock.systemUTC());
    }

    OperationsMetrics(MeterRegistry registry, ObjectProvider<JobQueue> jobQueue, Clock clock) {
        this.registry = registry;
        this.jobQueue = jobQueue;
        this.clock = clock;
        Gauge.builder(JOBS_QUEUED, this, metrics -> metrics.currentQueue().queued())
                .description("Background jobs waiting to be claimed").register(registry);
        Gauge.builder(JOBS_RUNNING, this, metrics -> metrics.currentQueue().running())
                .description("Background jobs currently leased by a worker").register(registry);
        Gauge.builder(JOBS_FAILED, this, metrics -> metrics.currentQueue().failed())
                .description("Background jobs in FAILED state (retrying or dead-lettered)").register(registry);
        Gauge.builder(BACKUP_LAST_SUCCESS, backupLastSuccessEpochSeconds, AtomicLong::doubleValue)
                .description("Unix time of the last successful backup check")
                .baseUnit("seconds")
                .register(registry);
    }

    public void recordQuartzJob(String jobName, boolean success, long durationNanos) {
        String job = boundedTag(jobName);
        Counter.builder(QUARTZ_JOB_EXECUTIONS)
                .description("Scheduled operations job runs")
                .tag("job", job)
                .tag("outcome", success ? "success" : "failure")
                .register(registry)
                .increment();
        Timer.builder(QUARTZ_JOB_DURATION)
                .description("Scheduled operations job duration")
                .tag("job", job)
                .register(registry)
                .record(Math.max(durationNanos, 0), TimeUnit.NANOSECONDS);
    }

    /** {@code outcome} is one of success, retry or dead_letter. */
    public void recordOutboxDispatch(BackgroundJob.JobKind kind, String outcome) {
        Counter.builder(OUTBOX_DISPATCHED)
                .description("Background jobs processed by the outbox dispatcher")
                .tag("kind", kind == null ? "unknown" : kind.name())
                .tag("outcome", boundedTag(outcome))
                .register(registry)
                .increment();
    }

    public void recordWebhookFailure(String reason) {
        Counter.builder(WEBHOOK_FAILURES)
                .description("Rejected or failed GitHub webhook deliveries")
                .tag("reason", boundedTag(reason))
                .register(registry)
                .increment();
    }

    public void recordAuditEvent(String entityType) {
        Counter.builder(AUDIT_EVENTS)
                .description("Application audit events recorded")
                .tag("entity_type", boundedTag(entityType))
                .register(registry)
                .increment();
    }

    public void recordSolverRun(String solver, String status, long durationNanos) {
        Timer.builder(SOLVER_DURATION)
                .description("Scheduling solver wall time")
                .tag("solver", boundedTag(solver))
                .tag("status", boundedTag(status))
                .register(registry)
                .record(Math.max(durationNanos, 0), TimeUnit.NANOSECONDS);
    }

    public void recordBackupSuccess(Instant checkedAt) {
        backupLastSuccessEpochSeconds.set((checkedAt == null ? clock.instant() : checkedAt).getEpochSecond());
    }

    /** Pushes a fresh queue snapshot (e.g. after a dispatch run) so gauges do not wait for the TTL. */
    public void updateQueue(JobQueue.Stats stats) {
        if (stats != null) {
            queueSnapshot.set(new QueueSnapshot(
                    stats.total(BackgroundJob.Status.QUEUED),
                    stats.total(BackgroundJob.Status.RUNNING),
                    stats.total(BackgroundJob.Status.FAILED),
                    clock.instant()));
        }
    }

    QueueSnapshot currentQueue() {
        QueueSnapshot snapshot = queueSnapshot.get();
        if (snapshot.capturedAt() == null || snapshot.capturedAt().plus(QUEUE_SNAPSHOT_TTL).isBefore(clock.instant())) {
            JobQueue queue = jobQueue.getIfAvailable();
            if (queue != null) {
                try {
                    updateQueue(queue.stats());
                } catch (RuntimeException ex) {
                    // Keep the last known values when the database is briefly unavailable.
                    queueSnapshot.compareAndSet(snapshot, new QueueSnapshot(snapshot.queued(), snapshot.running(),
                            snapshot.failed(), clock.instant()));
                }
            }
            snapshot = queueSnapshot.get();
        }
        return snapshot;
    }

    private static String boundedTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.strip();
        normalized = normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
        return normalized.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    record QueueSnapshot(long queued, long running, long failed, Instant capturedAt) {
        static final QueueSnapshot EMPTY = new QueueSnapshot(0, 0, 0, null);
    }
}
