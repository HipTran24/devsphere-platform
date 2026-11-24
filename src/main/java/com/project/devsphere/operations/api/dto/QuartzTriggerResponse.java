package com.project.devsphere.operations.api.dto;

import java.time.Instant;

/** State of one Quartz trigger attached to a whitelisted operations job. */
public record QuartzTriggerResponse(
        String jobName,
        String triggerName,
        String group,
        String description,
        String cronExpression,
        String timeZone,
        String state,
        Instant nextFireTime,
        Instant previousFireTime
) {
}
