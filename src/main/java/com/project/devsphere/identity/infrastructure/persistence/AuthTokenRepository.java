package com.project.devsphere.identity.infrastructure.persistence;

import com.project.devsphere.identity.domain.AuthToken;
import com.project.devsphere.sharedkernel.persistence.EntityRepository;

public interface AuthTokenRepository extends EntityRepository<AuthToken> {
}
