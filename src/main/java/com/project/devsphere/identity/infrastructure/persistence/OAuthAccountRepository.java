package com.project.devsphere.identity.infrastructure.persistence;

import com.project.devsphere.identity.domain.OAuthAccount;
import com.project.devsphere.sharedkernel.persistence.EntityRepository;

public interface OAuthAccountRepository extends EntityRepository<OAuthAccount> {
}
