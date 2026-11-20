package com.project.devsphere.identity.infrastructure.persistence;

import com.project.devsphere.identity.domain.AuthSession;
import com.project.devsphere.sharedkernel.persistence.EntityRepository;

public interface AuthSessionRepository extends EntityRepository<AuthSession> {
}
