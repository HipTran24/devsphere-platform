package com.project.devsphere.operations.infrastructure.quartz;

import com.project.devsphere.operations.application.OperationsScanService;
import com.project.devsphere.operations.application.OutboxDispatcherService;
import com.project.devsphere.operations.domain.BackgroundJob;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * Schedules a backfill for every active GitHub repository and processes the GITHUB_BACKFILL queue.
 */
@DisallowConcurrentExecution
public class GitHubBackfillSyncJob extends AbstractOperationsJob {

    public static final String NAME = "githubBackfillSync";
    static final int BATCH_SIZE = 100;

    private OperationsScanService scanService;
    private OutboxDispatcherService dispatcher;

    @Autowired
    public void setOperationsScanService(OperationsScanService scanService) {
        this.scanService = scanService;
    }

    @Autowired
    public void setOutboxDispatcherService(OutboxDispatcherService dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public String jobName() {
        return NAME;
    }

    @Override
    protected Map<String, Object> run(JobExecutionContext context) {
        int enqueued = scanService.enqueueGithubBackfills();
        OutboxDispatcherService.DispatchSummary dispatched = dispatcher.dispatchKind(BackgroundJob.JobKind.GITHUB_BACKFILL, BATCH_SIZE);
        return Map.of("backfillsEnqueued", enqueued, "processed", dispatched.claimed(), "succeeded", dispatched.succeeded());
    }
}
