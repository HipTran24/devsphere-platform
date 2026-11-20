package com.project.devsphere.sharedkernel.persistence;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/** For global identities only. Project repositories deliberately omit unscoped reads. */
@NoRepositoryBean
public interface EntityRepository<T extends UuidEntity> extends Repository<T, UUID> {
    <S extends T> S save(S entity);
    Optional<T> findById(UUID id);
    void flush();
}
