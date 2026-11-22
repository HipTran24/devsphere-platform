package com.project.devsphere.resource.application;

import com.project.devsphere.resource.api.ResourceCapacitySnapshot;
import com.project.devsphere.resource.api.SkillLevel;
import com.project.devsphere.resource.api.WeeklySlot;
import com.project.devsphere.resource.domain.DeveloperCapacity;
import com.project.devsphere.resource.infrastructure.persistence.DeveloperCapacityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResourceCapacitySnapshotServiceTest {

    @Mock private DeveloperCapacityRepository capacityRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    private ResourceCapacitySnapshotService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID dev1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID dev2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        service = new ResourceCapacitySnapshotService(capacityRepository, jdbcTemplate);

        when(jdbcTemplate.queryForList(contains("FROM projects WHERE id = ?"), eq(projectId)))
                .thenReturn(List.of(Map.of(
                        "workspace_id", workspaceId,
                        "version", 5L,
                        "timezone", "Asia/Ho_Chi_Minh"
                )));

        when(jdbcTemplate.query(contains("FROM project_members"), any(RowMapper.class), eq(projectId)))
                .thenReturn(List.of(dev1, dev2));
    }

    @Test
    @DisplayName("read returns canonical immutable snapshot with sorted developers and intervals")
    void read_returnsCanonicalSnapshot() {
        DeveloperCapacity cap1 = new DeveloperCapacity();
        cap1.setId(UUID.randomUUID());
        cap1.setProjectId(projectId);
        cap1.setUserId(dev2); // dev2 has later UUID
        cap1.setEffectiveFrom(LocalDate.of(2026, 12, 1));
        cap1.setEffectiveTo(null);
        cap1.setMinutesPerWorkday(480);
        cap1.setAllocationPct(new BigDecimal("75.00")); // budget: 360
        cap1.setSkills(List.of(new SkillLevel("java", 5)));
        cap1.setWeeklySlots(List.of(new WeeklySlot(1, 540, 1020)));
        cap1.setVersion(1L);

        DeveloperCapacity cap2 = new DeveloperCapacity();
        cap2.setId(UUID.randomUUID());
        cap2.setProjectId(projectId);
        cap2.setUserId(dev1); // dev1 has earlier UUID
        cap2.setEffectiveFrom(LocalDate.of(2026, 12, 1));
        cap2.setEffectiveTo(LocalDate.of(2026, 12, 15));
        cap2.setMinutesPerWorkday(480);
        cap2.setAllocationPct(new BigDecimal("100.00")); // budget: 480
        cap2.setSkills(List.of(new SkillLevel("react", 4)));
        cap2.setWeeklySlots(List.of(new WeeklySlot(1, 540, 1020)));
        cap2.setVersion(2L);

        when(capacityRepository.findByProjectIdOrderByUserIdAscEffectiveFromAsc(projectId))
                .thenReturn(List.of(cap2, cap1));

        ResourceCapacitySnapshot snapshot = service.read(projectId, null, null);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.projectId()).isEqualTo(projectId);
        assertThat(snapshot.workspaceId()).isEqualTo(workspaceId);
        assertThat(snapshot.projectVersion()).isEqualTo(5L);
        assertThat(snapshot.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(snapshot.capturedAt()).isNotNull();

        assertThat(snapshot.developers()).hasSize(2);
        // Canonical ordering: dev1 before dev2
        assertThat(snapshot.developers().get(0).developerId()).isEqualTo(dev1);
        assertThat(snapshot.developers().get(0).eligible()).isTrue();
        assertThat(snapshot.developers().get(0).intervals()).hasSize(1);
        assertThat(snapshot.developers().get(0).intervals().get(0).dailyBudgetMinutes()).isEqualTo(480);

        assertThat(snapshot.developers().get(1).developerId()).isEqualTo(dev2);
        assertThat(snapshot.developers().get(1).eligible()).isTrue();
        assertThat(snapshot.developers().get(1).intervals()).hasSize(1);
        assertThat(snapshot.developers().get(1).intervals().get(0).dailyBudgetMinutes()).isEqualTo(360);
    }

    @Test
    @DisplayName("read with date range invokes findAllIntersecting")
    void read_withDateRange_invokesFindAllIntersecting() {
        LocalDate from = LocalDate.of(2026, 12, 1);
        LocalDate to = LocalDate.of(2026, 12, 15);

        when(capacityRepository.findAllIntersecting(projectId, from, to))
                .thenReturn(List.of());

        ResourceCapacitySnapshot snapshot = service.read(projectId, from, to);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.developers()).hasSize(2);
        assertThat(snapshot.developers().get(0).intervals()).isEmpty();
    }

    @Test
    @DisplayName("read throws 404 when project does not exist")
    void read_projectNotFound_throws404() {
        when(jdbcTemplate.queryForList(contains("FROM projects WHERE id = ?"), eq(projectId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.read(projectId, null, null))
                .isInstanceOf(ResourceCapacityException.class)
                .satisfies(ex -> assertThat(((ResourceCapacityException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("read throws 400 when projectId is null")
    void read_nullProjectId_throws400() {
        assertThatThrownBy(() -> service.read(null, null, null))
                .isInstanceOf(ResourceCapacityException.class)
                .satisfies(ex -> assertThat(((ResourceCapacityException) ex).getStatus().value()).isEqualTo(400));
    }
}
