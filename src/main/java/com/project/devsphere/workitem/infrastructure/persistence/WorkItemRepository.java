package com.project.devsphere.workitem.infrastructure.persistence;

import com.project.devsphere.workitem.domain.WorkItem;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.UUID;

public interface WorkItemRepository extends ProjectScopedRepository<WorkItem> {

    long countByProjectIdAndDeletedAtIsNull(UUID projectId);

    boolean existsByProjectIdAndDeletedAtIsNull(UUID projectId);
}
