package com.project.devsphere.sharedkernel.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

@NoRepositoryBean
public interface WorkspaceScopedRepository<T extends UuidEntity> extends Repository<T, UUID> {
    <S extends T> S save(S entity);
    Optional<T> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    Page<T> findAllByWorkspaceId(UUID workspaceId, Pageable pageable);
    void flush();
}
