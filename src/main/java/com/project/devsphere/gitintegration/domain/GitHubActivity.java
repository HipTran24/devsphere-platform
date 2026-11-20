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

/** Persistence mapping for github_activities; cross-module references remain UUIDs. */
@Entity
@Table(name = "github_activities")
@Getter
@Setter
public class GitHubActivity extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "work_item_id", nullable = true)
    private UUID workItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "github_object_id", nullable = false, length = 100)
    private String githubObjectId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "commit_sha", nullable = true, length = 40, columnDefinition = "char(40)")
    private String commitSha;

    @Column(name = "pr_number", nullable = true)
    private Integer prNumber;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pr_head_sha", nullable = true, length = 40, columnDefinition = "char(40)")
    private String prHeadSha;

    @Column(name = "actor_login", nullable = true, length = 150)
    private String actorLogin;

    @Column(name = "state", nullable = false, length = 60)
    private String state;

    @Column(name = "title", nullable = true, columnDefinition = "text")
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Column(name = "happened_at", nullable = false)
    private Instant happenedAt;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt = Instant.now();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum Kind { BRANCH, COMMIT, PULL_REQUEST, HUMAN_REVIEW, QUALITY_GATE }
}
