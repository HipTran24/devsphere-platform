package com.project.devsphere.operations.infrastructure.quartz;

import com.project.devsphere.operations.application.OperationsScanService;
import com.project.devsphere.operations.application.OutboxDispatcherService;
import com.project.devsphere.operations.domain.BackgroundJob;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * Schedules yesterday's DAILY report draft for every active project and generates the drafts.
 */
@DisallowConcurrentExecution
public class DailyReportScheduleJob extends AbstractOperationsJob {

    public static final String NAME = "dailyReportSchedule";
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
        int enqueued = scanService.enqueueDailyReports();
        OutboxDispatcherService.DispatchSummary dispatched = dispatcher.dispatchKind(BackgroundJob.JobKind.REPORT_EXPORT, BATCH_SIZE);
        return Map.of("reportsEnqueued", enqueued, "processed", dispatched.claimed(), "succeeded", dispatched.succeeded());
    }
}
