package com.project.devsphere.operations.infrastructure.metrics;

import com.project.devsphere.operations.domain.BackgroundJob;
import com.project.devsphere.operations.infrastructure.persistence.JobQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationsMetricsTest {

    private SimpleMeterRegistry registry;
    private JobQueue queue;
    private MutableClock clock;
    private OperationsMetrics metrics;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new SimpleMeterRegistry();
        queue = mock(JobQueue.class);
        ObjectProvider<JobQueue> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(queue);
        clock = new MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
        metrics = new OperationsMetrics(registry, provider, clock);
    }

    private static JobQueue.Stats stats(long queued, long running, long failed) {
        return new JobQueue.Stats(Map.of(), Map.of("QUEUED", queued, "RUNNING", running, "FAILED", failed, "SUCCESS", 0L), 0, 0, null);
    }

    @Test
    void quartzExecutionsAreCountedPerJobAndOutcomeWithDuration() {
        metrics.recordQuartzJob("nightlyBackupCheck", true, TimeUnit.MILLISECONDS.toNanos(250));
        metrics.recordQuartzJob("nightlyBackupCheck", true, TimeUnit.MILLISECONDS.toNanos(150));
        metrics.recordQuartzJob("nightlyBackupCheck", false, 1_000);

        assertThat(registry.get(OperationsMetrics.QUARTZ_JOB_EXECUTIONS).tags("job", "nightlyBackupCheck", "outcome", "success").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get(OperationsMetrics.QUARTZ_JOB_EXECUTIONS).tags("outcome", "failure").counter().count()).isEqualTo(1.0);
        var timer = registry.get(OperationsMetrics.QUARTZ_JOB_DURATION).tag("job", "nightlyBackupCheck").timer();
        assertThat(timer.count()).isEqualTo(3);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(400.0);
    }

    @Test
    void outboxWebhookAuditAndSolverMetersUseBoundedTags() {
        metrics.recordOutboxDispatch(BackgroundJob.JobKind.OUTBOX_EVENT, "success");
        metrics.recordOutboxDispatch(BackgroundJob.JobKind.OUTBOX_EVENT, "retry");
        metrics.recordOutboxDispatch(null, null);
        metrics.recordWebhookFailure("invalid signature!");
        metrics.recordAuditEvent("quartz_job");
        metrics.recordSolverRun("cp-sat", "OPTIMAL", TimeUnit.SECONDS.toNanos(2));

        assertThat(registry.get(OperationsMetrics.OUTBOX_DISPATCHED).tags("kind", "OUTBOX_EVENT", "outcome", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(OperationsMetrics.OUTBOX_DISPATCHED).tags("kind", "unknown", "outcome", "unknown").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(OperationsMetrics.WEBHOOK_FAILURES).tag("reason", "invalid_signature_").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(OperationsMetrics.AUDIT_EVENTS).tag("entity_type", "quartz_job").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(OperationsMetrics.SOLVER_DURATION).tags("solver", "cp-sat", "status", "OPTIMAL").timer().totalTime(TimeUnit.SECONDS))
                .isEqualTo(2.0);
    }

    @Test
    void queueGaugesAreCachedAndRefreshedAfterTtl() {
        when(queue.stats()).thenReturn(stats(4, 2, 1), stats(10, 0, 3));

        assertThat(registry.get(OperationsMetrics.JOBS_QUEUED).gauge().value()).isEqualTo(4.0);
        assertThat(registry.get(OperationsMetrics.JOBS_RUNNING).gauge().value()).isEqualTo(2.0);
        assertThat(registry.get(OperationsMetrics.JOBS_FAILED).gauge().value()).isEqualTo(1.0);
        verify(queue, times(1)).stats();

        clock.advanceSeconds(OperationsMetrics.QUEUE_SNAPSHOT_TTL.toSeconds() + 1);
        assertThat(registry.get(OperationsMetrics.JOBS_QUEUED).gauge().value()).isEqualTo(10.0);
        assertThat(registry.get(OperationsMetrics.JOBS_FAILED).gauge().value()).isEqualTo(3.0);
        verify(queue, times(2)).stats();
    }

    @Test
    void queueGaugesKeepLastValuesWhenDatabaseFails() {
        metrics.updateQueue(stats(7, 1, 0));
        clock.advanceSeconds(60);
        when(queue.stats()).thenThrow(new IllegalStateException("database down"));

        assertThat(registry.get(OperationsMetrics.JOBS_QUEUED).gauge().value()).isEqualTo(7.0);
    }

    @Test
    void backupTimestampGaugeReportsEpochSeconds() {
        assertThat(registry.get(OperationsMetrics.BACKUP_LAST_SUCCESS).gauge().value()).isZero();
        metrics.recordBackupSuccess(Instant.parse("2026-11-23T02:00:05Z"));
        assertThat(registry.get(OperationsMetrics.BACKUP_LAST_SUCCESS).gauge().value())
                .isEqualTo((double) Instant.parse("2026-11-23T02:00:05Z").getEpochSecond());
    }

    @Test
    @SuppressWarnings("unchecked")
    void prometheusExpositionUsesTheDocumentedMetricNames() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ObjectProvider<JobQueue> provider = mock(ObjectProvider.class);
        OperationsMetrics exported = new OperationsMetrics(prometheus, provider, Clock.systemUTC());
        exported.recordQuartzJob("outboxDispatch", true, 1_000_000);
        exported.recordOutboxDispatch(BackgroundJob.JobKind.BACKUP_CHECK, "success");
        exported.recordWebhookFailure("invalid_signature");
        exported.recordAuditEvent("background_job");
        exported.recordSolverRun("greedy", "FEASIBLE", 5_000_000);
        exported.recordBackupSuccess(Instant.now());

        String scrape = prometheus.scrape();
        assertThat(scrape).contains(
                OperationsMetrics.PROMETHEUS_QUARTZ_JOB_EXECUTIONS + "{",
                OperationsMetrics.PROMETHEUS_OUTBOX_DISPATCHED + "{",
                OperationsMetrics.PROMETHEUS_WEBHOOK_FAILURES + "{",
                OperationsMetrics.PROMETHEUS_AUDIT_EVENTS + "{",
                OperationsMetrics.PROMETHEUS_QUARTZ_JOB_DURATION + "_count{",
                OperationsMetrics.PROMETHEUS_SOLVER_DURATION + "_count{",
                OperationsMetrics.PROMETHEUS_JOBS_QUEUED + " ",
                OperationsMetrics.PROMETHEUS_JOBS_RUNNING + " ",
                OperationsMetrics.PROMETHEUS_JOBS_FAILED + " ",
                OperationsMetrics.PROMETHEUS_BACKUP_LAST_SUCCESS + " ");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
