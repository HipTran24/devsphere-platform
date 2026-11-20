package com.project.devsphere.identity.infrastructure.persistence;

import com.project.devsphere.identity.domain.User;
import com.project.devsphere.sharedkernel.persistence.EntityRepository;

public interface UserRepository extends EntityRepository<User> {

    java.util.Optional<User> findByEmailIgnoreCase(String email);
}
