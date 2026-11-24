package com.project.devsphere.operations.infrastructure.quartz;

import com.project.devsphere.operations.application.OperationsScanService;
import com.project.devsphere.operations.application.OutboxDispatcherService;
import com.project.devsphere.operations.domain.BackgroundJob;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * Enqueues and runs the nightly BACKUP_CHECK verifying database health and backup freshness.
 */
@DisallowConcurrentExecution
public class NightlyBackupCheckJob extends AbstractOperationsJob {

    public static final String NAME = "nightlyBackupCheck";

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
        int enqueued = scanService.enqueueBackupCheck();
        OutboxDispatcherService.DispatchSummary dispatched = dispatcher.dispatchKind(BackgroundJob.JobKind.BACKUP_CHECK, 5);
        return Map.of("checksEnqueued", enqueued, "processed", dispatched.claimed(), "succeeded", dispatched.succeeded());
    }
}
