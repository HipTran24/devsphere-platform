package com.project.devsphere.operations.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Outcome stored in {@code background_jobs.result} when a handler succeeds. */
public record JobResult(Map<String, Object> details) {

    public JobResult {
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static JobResult of(Map<String, Object> details) {
        return new JobResult(details);
    }

    public static JobResult skipped(String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outcome", "SKIPPED");
        details.put("reason", reason);
        return new JobResult(details);
    }
}
