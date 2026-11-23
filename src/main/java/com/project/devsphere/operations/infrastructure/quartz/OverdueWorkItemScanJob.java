package com.project.devsphere.operations.infrastructure.quartz;

import com.project.devsphere.operations.application.OperationsScanService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * Scans open work items whose due date has passed and enqueues one WORK_ITEM_OVERDUE outbox event
 * per item per day; {@link OutboxAndEmailDispatchJob} publishes them.
 */
@DisallowConcurrentExecution
public class OverdueWorkItemScanJob extends AbstractOperationsJob {

    public static final String NAME = "overdueWorkItemScan";

    private OperationsScanService scanService;

    @Autowired
    public void setOperationsScanService(OperationsScanService scanService) {
        this.scanService = scanService;
    }

    @Override
    public String jobName() {
        return NAME;
    }

    @Override
    protected Map<String, Object> run(JobExecutionContext context) {
        int enqueued = scanService.enqueueOverdueWorkItems();
        return Map.of("overdueEventsEnqueued", enqueued);
    }
}
