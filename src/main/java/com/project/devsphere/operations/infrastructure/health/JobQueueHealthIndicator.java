package com.project.devsphere.operations.infrastructure.health;

import com.project.devsphere.operations.infrastructure.metrics.OperationsMetrics;
import com.project.devsphere.operations.infrastructure.persistence.JobQueue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Durable background queue health. The queue is UP while it can be queried; expired leases and
 * dead-lettered jobs are surfaced as details (and flag {@code degraded=true}) rather than taking the
 * whole application out of rotation.
 */
@Component
public class JobQueueHealthIndicator implements HealthIndicator {

    private final JobQueue jobQueue;
    private final ObjectProvider<OperationsMetrics> metrics;

    public JobQueueHealthIndicator(JobQueue jobQueue, ObjectProvider<OperationsMetrics> metrics) {
        this.jobQueue = jobQueue;
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        JobQueue.Stats stats;
        try {
            stats = jobQueue.stats();
        } catch (RuntimeException ex) {
            return Health.down().withDetail("error", ex.getClass().getSimpleName()).build();
        }
        metrics.ifAvailable(m -> m.updateQueue(stats));
        boolean degraded = stats.expiredLeases() > 0 || stats.deadLettered() > 0;
        return Health.up()
                .withDetail("totalsByStatus", stats.totalsByStatus())
                .withDetail("expiredLeases", stats.expiredLeases())
                .withDetail("deadLettered", stats.deadLettered())
                .withDetail("degraded", degraded)
                .build();
    }
}
