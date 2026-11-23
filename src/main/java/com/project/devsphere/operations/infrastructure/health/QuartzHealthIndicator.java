package com.project.devsphere.operations.infrastructure.health;

import com.project.devsphere.operations.infrastructure.quartz.QuartzJobManagementService;
import com.project.devsphere.operations.infrastructure.quartz.QuartzSchedulerConfig;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the in-memory Quartz scheduler: UP when started with every operations job registered,
 * UNKNOWN while intentionally held in standby (auto-startup disabled), DOWN when shut down or when
 * jobs are missing.
 */
@Component
public class QuartzHealthIndicator implements HealthIndicator {

    private final QuartzJobManagementService quartzJobs;

    public QuartzHealthIndicator(QuartzJobManagementService quartzJobs) {
        this.quartzJobs = quartzJobs;
    }

    @Override
    public Health health() {
        QuartzJobManagementService.SchedulerStatus status = quartzJobs.status();
        int expected = QuartzSchedulerConfig.JOB_NAMES.size();
        Health.Builder builder;
        if (!status.available() || status.shutdown() || status.jobCount() < expected) {
            builder = Health.down();
        } else if (!status.started() || status.inStandby()) {
            builder = Health.unknown();
        } else {
            builder = Health.up();
        }
        return builder
                .withDetail("started", status.started())
                .withDetail("standby", status.inStandby())
                .withDetail("jobs", status.jobCount())
                .withDetail("expectedJobs", expected)
                .build();
    }
}
