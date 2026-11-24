package com.project.devsphere.operations.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Startup runner that invokes {@link DemoSeedDataService#seedDemoData()} only when
 * {@code devsphere.seed.demo.enabled=true} AND the {@code prod} profile is not active.
 */
@Component
@ConditionalOnProperty(name = "devsphere.seed.demo.enabled", havingValue = "true")
@Profile("!prod")
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

    private final DemoSeedDataService demoSeedDataService;

    public DemoSeedRunner(DemoSeedDataService demoSeedDataService) {
        this.demoSeedDataService = demoSeedDataService;
    }

    @Override
    public void run(ApplicationArguments args) {
        DemoSeedResultResponse result = demoSeedDataService.seedDemoData();
        log.info("Demo seed data initialized for workspace={} project={}",
                result.workspaceSlug(), result.projectKey());
    }
}
