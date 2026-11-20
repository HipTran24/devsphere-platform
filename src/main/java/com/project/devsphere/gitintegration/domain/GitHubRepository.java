package com.project.devsphere.gitintegration.domain;

import com.project.devsphere.sharedkernel.persistence.UuidEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Persistence mapping for github_repositories; cross-module references remain UUIDs. */
@Entity
@Table(name = "github_repositories")
@Getter
@Setter
public class GitHubRepository extends UuidEntity {

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "github_installation_id", nullable = false)
    private Long githubInstallationId;

    @Column(name = "github_repo_id", nullable = false)
    private Long githubRepoId;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "default_branch", nullable = false, length = 120)
    private String defaultBranch;

    @Column(name = "private", nullable = false)
    private Boolean privateRepository = true;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @Column(name = "sync_cursor", nullable = true, columnDefinition = "text")
    private String syncCursor;

    @Column(name = "last_synced_at", nullable = true)
    private Instant lastSyncedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;


}
