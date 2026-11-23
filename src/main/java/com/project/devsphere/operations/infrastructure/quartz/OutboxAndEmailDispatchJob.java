package com.project.devsphere.operations.infrastructure.quartz;

import com.project.devsphere.operations.application.OutboxDispatcherService;
import com.project.devsphere.operations.domain.BackgroundJob;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * Publishes pending outbox events and delivers queued notification e-mails.
 */
@DisallowConcurrentExecution
public class OutboxAndEmailDispatchJob extends AbstractOperationsJob {

    public static final String NAME = "outboxDispatch";
    static final int BATCH_SIZE = 100;

    private OutboxDispatcherService dispatcher;

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
        OutboxDispatcherService.DispatchSummary events = dispatcher.dispatchKind(BackgroundJob.JobKind.OUTBOX_EVENT, BATCH_SIZE);
        OutboxDispatcherService.DispatchSummary emails = dispatcher.dispatchKind(BackgroundJob.JobKind.NOTIFICATION_EMAIL, BATCH_SIZE);
        return Map.of("outboxEventsProcessed", events.claimed(), "emailsProcessed", emails.claimed(),
                "failed", events.failed() + emails.failed());
    }
}
