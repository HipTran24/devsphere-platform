package com.project.devsphere.workitem.infrastructure.persistence;

import com.project.devsphere.workitem.domain.WorkItem;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

public interface WorkItemRepository extends ProjectScopedRepository<WorkItem> {
}
