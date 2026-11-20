package com.project.devsphere.notification.infrastructure.persistence;

import com.project.devsphere.notification.domain.Notification;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

public interface NotificationRepository extends ProjectScopedRepository<Notification> {
}
