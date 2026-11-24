package com.project.devsphere.storage.infrastructure;

import com.project.devsphere.storage.application.StorageProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Verifies that the active storage adapter can sign URLs (credentials, region and endpoint are
 * usable) by presigning a probe key. No network call is made and the URL is never exposed.
 */
@Component
public class StorageHealthIndicator implements HealthIndicator {

    static final String PROBE_KEY = "health/probe.txt";

    private final StorageProvider storageProvider;

    public StorageHealthIndicator(StorageProvider storageProvider) {
        this.storageProvider = storageProvider;
    }

    @Override
    public Health health() {
        try {
            String url = storageProvider.generatePresignedDownloadUrl(PROBE_KEY, 60);
            if (url == null || url.isBlank()) {
                return Health.down().withDetail("mode", storageProvider.mode()).withDetail("error", "EMPTY_URL").build();
            }
            return Health.up()
                    .withDetail("mode", storageProvider.mode())
                    .withDetail("bucket", storageProvider.bucket())
                    .build();
        } catch (RuntimeException ex) {
            return Health.down()
                    .withDetail("mode", storageProvider.mode())
                    .withDetail("error", ex.getClass().getSimpleName())
                    .build();
        }
    }
}
