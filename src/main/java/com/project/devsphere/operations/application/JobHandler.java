package com.project.devsphere.operations.application;

import com.project.devsphere.operations.domain.BackgroundJob;

/**
 * Processes one kind of background job. Handlers run inside the dispatcher's transaction together
 * with the job completion, so their database side effects commit atomically with SUCCESS and roll
 * back when they throw. Delivery is at least once: implementations must be idempotent.
 *
 * <p>Throwing {@link NonRetryableJobException} dead-letters the job immediately; any other
 * exception schedules a retry with exponential backoff.</p>
 */
public interface JobHandler {

    BackgroundJob.JobKind kind();

    JobResult handle(ClaimedJob job) throws Exception;
}
