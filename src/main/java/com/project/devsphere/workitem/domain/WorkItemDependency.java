package com.project.devsphere.workitem.domain;

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

/** Persistence mapping for work_item_dependencies; cross-module references remain UUIDs. */
@Entity
@Table(name = "work_item_dependencies")
@Getter
@Setter
public class WorkItemDependency extends UuidEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "predecessor_id", nullable = false)
    private UUID predecessorId;

    @Column(name = "successor_id", nullable = false)
    private UUID successorId;

    @Column(name = "link_type", nullable = false, length = 24)
    private String linkType = "FINISH_TO_START";

    @Column(name = "lag_minutes", nullable = false)
    private Integer lagMinutes = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;


}
