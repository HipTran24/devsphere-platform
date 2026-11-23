package com.project.devsphere.sprint.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.sprint.api.dto.CreateSprintRequest;
import com.project.devsphere.sprint.api.dto.StartSprintRequest;
import com.project.devsphere.sprint.domain.Sprint;
import com.project.devsphere.sprint.infrastructure.persistence.SprintRepository;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SprintServiceTest {

    @Mock private SprintRepository sprintRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AccessPolicy accessPolicy;

    private SprintService sprintService;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID sprintId = UUID.randomUUID();
    private SessionService.Principal principal;

    @BeforeEach
    void setUp() {
        sprintService = new SprintService(sprintRepository, jdbcTemplate, accessPolicy);
        principal = new SessionService.Principal(userId, sessionId, "user@devsphere.test", "USER");

        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyString()))
                .thenReturn(userId.toString());
    }

    @Test
    @DisplayName("Rejects sprint creation when endDate is not after startDate")
    void rejectsInvalidDatesOnCreation() {
        CreateSprintRequest request = new CreateSprintRequest(
                "Sprint 1",
                "Goal",
                LocalDate.of(2026, 12, 14),
                LocalDate.of(2026, 12, 10)
        );

        assertThatThrownBy(() -> sprintService.createSprint(principal, workspaceId, projectId, request))
                .isInstanceOf(WorkspaceProjectException.class)
                .hasMessageContaining("after startDate");

        verify(accessPolicy).requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);
    }

    @Test
    @DisplayName("Starting sprint is rejected when another sprint is already ACTIVE in project")
    void rejectsStartingWhenActiveSprintAlreadyExists() {
        Map<String, Object> sprintRow = Map.of(
                "id", sprintId,
                "status", "PLANNED",
                "version", 0L
        );

        when(jdbcTemplate.queryForList(anyString(), eq(sprintId), eq(projectId)))
                .thenReturn(List.of(sprintRow));

        when(sprintRepository.existsByProjectIdAndStatus(eq(projectId), eq(Sprint.Status.ACTIVE)))
                .thenReturn(true);

        StartSprintRequest req = new StartSprintRequest(0L);

        assertThatThrownBy(() -> sprintService.startSprint(principal, workspaceId, projectId, sprintId, req))
                .isInstanceOf(WorkspaceProjectException.class)
                .hasMessageContaining("already has an active sprint");
    }

    @Test
    @DisplayName("Modifying items in a CLOSED sprint is rejected with SPRINT_CLOSED")
    void rejectsModifyingItemsInClosedSprint() {
        ProjectScope scope = new ProjectScope(workspaceId, projectId, userId, Set.of(ProjectRole.PM));
        when(accessPolicy.requireProjectMember(any(), any(), any())).thenReturn(scope);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(sprintId), eq(projectId)))
                .thenReturn(List.of("CLOSED"));

        assertThatThrownBy(() -> sprintService.modifySprintItems(principal, workspaceId, projectId, sprintId, "ADD", List.of(UUID.randomUUID())))
                .isInstanceOf(WorkspaceProjectException.class)
                .hasMessageContaining("Cannot modify items in a closed sprint");
    }

    @Test
    @DisplayName("Modifying DONE work item sprint assignment is rejected with CANNOT_MODIFY_DONE_ITEM_SPRINT")
    void rejectsModifyingDoneItemSprint() {
        ProjectScope scope = new ProjectScope(workspaceId, projectId, userId, Set.of(ProjectRole.PM));
        when(accessPolicy.requireProjectMember(any(), any(), any())).thenReturn(scope);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(sprintId), eq(projectId)))
                .thenReturn(List.of("ACTIVE"));

        UUID itemId = UUID.randomUUID();
        Map<String, Object> itemRow = Map.of(
                "id", itemId,
                "status", "DONE",
                "sprint_id", UUID.randomUUID()
        );

        when(jdbcTemplate.queryForList(anyString(), eq(itemId), eq(projectId)))
                .thenReturn(List.of(itemRow));

        assertThatThrownBy(() -> sprintService.modifySprintItems(principal, workspaceId, projectId, sprintId, "ADD", List.of(itemId)))
                .isInstanceOf(WorkspaceProjectException.class)
                .hasMessageContaining("Cannot modify sprint of a DONE work item");
    }
}
