package com.project.devsphere.resource.application;

import com.project.devsphere.resource.api.ResourceCapacitySnapshot;
import com.project.devsphere.resource.api.ResourceCapacitySnapshotReader;
import com.project.devsphere.resource.domain.DeveloperCapacity;
import com.project.devsphere.resource.infrastructure.persistence.DeveloperCapacityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ResourceCapacitySnapshotService implements ResourceCapacitySnapshotReader {

    private final DeveloperCapacityRepository capacityRepository;
    private final JdbcTemplate jdbcTemplate;

    public ResourceCapacitySnapshotService(
            DeveloperCapacityRepository capacityRepository,
            JdbcTemplate jdbcTemplate) {
        this.capacityRepository = Objects.requireNonNull(capacityRepository, "capacityRepository must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public ResourceCapacitySnapshot read(UUID projectId, LocalDate from, LocalDate to) {
        if (projectId == null) {
            throw ResourceCapacityException.validationFailed("projectId must not be null");
        }

        // Fetch project info: workspace_id, version, timezone
        List<Map<String, Object>> projectRows = jdbcTemplate.queryForList(
                "SELECT workspace_id, version, timezone FROM projects WHERE id = ?",
                projectId
        );
        if (projectRows.isEmpty()) {
            throw ResourceCapacityException.notFound("Project not found with ID: " + projectId);
        }

        Map<String, Object> projectRow = projectRows.get(0);
        UUID workspaceId = (UUID) projectRow.get("workspace_id");
        Long projectVersion = ((Number) projectRow.get("version")).longValue();
        String timezone = (String) projectRow.get("timezone");
        if (timezone == null || timezone.isBlank()) {
            timezone = "Asia/Ho_Chi_Minh";
        }

        // Query all capacities intersecting [from, to] or all if from/to are null
        List<DeveloperCapacity> capacities;
        if (from != null || to != null) {
            capacities = capacityRepository.findAllIntersecting(projectId, from, to);
        } else {
            capacities = capacityRepository.findByProjectIdOrderByUserIdAscEffectiveFromAsc(projectId);
        }

        // Query all developer users in project (to get eligibility)
        List<UUID> developerUserIds = jdbcTemplate.query(
                "SELECT DISTINCT m.user_id FROM project_members m " +
                "JOIN project_member_roles r ON r.project_member_id = m.id " +
                "WHERE m.project_id = ? AND r.role = 'DEVELOPER'",
                (rs, rowNum) -> UUID.fromString(rs.getString("user_id")),
                projectId
        );

        // Group capacities by developer (userId)
        Map<UUID, List<DeveloperCapacity>> capacitiesByDeveloper = new LinkedHashMap<>();
        for (UUID devId : developerUserIds) {
            capacitiesByDeveloper.put(devId, new ArrayList<>());
        }
        for (DeveloperCapacity cap : capacities) {
            capacitiesByDeveloper.computeIfAbsent(cap.getUserId(), k -> new ArrayList<>()).add(cap);
        }

        List<ResourceCapacitySnapshot.DeveloperCapacityRecord> developerRecords = new ArrayList<>();
        for (Map.Entry<UUID, List<DeveloperCapacity>> entry : capacitiesByDeveloper.entrySet()) {
            UUID devId = entry.getKey();
            List<DeveloperCapacity> devCaps = entry.getValue();
            devCaps.sort(Comparator.comparing(DeveloperCapacity::getEffectiveFrom));

            boolean eligible = developerUserIds.contains(devId);

            List<ResourceCapacitySnapshot.CapacityInterval> intervals = new ArrayList<>();
            for (DeveloperCapacity cap : devCaps) {
                int minutesPerWorkday = cap.getMinutesPerWorkday() != null ? cap.getMinutesPerWorkday() : 480;
                BigDecimal allocPct = cap.getAllocationPct() != null ? cap.getAllocationPct() : new BigDecimal("100");
                int dailyBudget = BigDecimal.valueOf(minutesPerWorkday)
                        .multiply(allocPct)
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR)
                        .intValue();

                intervals.add(new ResourceCapacitySnapshot.CapacityInterval(
                        cap.getId(),
                        cap.getVersion(),
                        cap.getEffectiveFrom(),
                        cap.getEffectiveTo(),
                        minutesPerWorkday,
                        allocPct,
                        dailyBudget,
                        cap.getSkills() != null ? cap.getSkills() : List.of(),
                        cap.getWeeklySlots() != null ? cap.getWeeklySlots() : List.of(),
                        cap.getTimeOff() != null ? cap.getTimeOff() : List.of()
                ));
            }

            developerRecords.add(new ResourceCapacitySnapshot.DeveloperCapacityRecord(
                    devId,
                    eligible,
                    intervals
            ));
        }

        // Sort developers canonically by developerId
        developerRecords.sort(Comparator.comparing(ResourceCapacitySnapshot.DeveloperCapacityRecord::developerId));

        return new ResourceCapacitySnapshot(
                projectId,
                workspaceId,
                projectVersion,
                timezone,
                Instant.now(),
                developerRecords
        );
    }
}
