package com.project.devsphere.resource.api;

import java.time.LocalDate;
import java.util.UUID;

public interface ResourceCapacitySnapshotReader {

    ResourceCapacitySnapshot read(UUID projectId, LocalDate from, LocalDate to);
}
