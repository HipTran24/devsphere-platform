package com.project.devsphere.sharedkernel.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

@NoRepositoryBean
public interface ProjectScopedRepository<T extends UuidEntity> extends Repository<T, UUID> {
    <S extends T> S save(S entity);
    Optional<T> findByIdAndProjectId(UUID id, UUID projectId);
    Page<T> findAllByProjectId(UUID projectId, Pageable pageable);
    boolean existsByIdAndProjectId(UUID id, UUID projectId);
    void flush();
}
