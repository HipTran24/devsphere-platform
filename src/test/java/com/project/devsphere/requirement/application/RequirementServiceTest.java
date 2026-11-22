package com.project.devsphere.requirement.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.requirement.api.dto.CreateRequirementRequest;
import com.project.devsphere.requirement.api.dto.RequirementResponse;
import com.project.devsphere.requirement.api.dto.UpdateRequirementRequest;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AccessPolicy accessPolicy;

    private RequirementService requirementService;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private SessionService.Principal pmPrincipal;

    @BeforeEach
    void setUp() {
        requirementService = new RequirementService(jdbcTemplate, accessPolicy);
        pmPrincipal = new SessionService.Principal(userId, sessionId, "PM User", "USER");
    }

    @Test
    @DisplayName("createRequirement fails if title is blank")
    void createRequirementBlankTitle() {
        assertThatThrownBy(() -> requirementService.createRequirement(
                pmPrincipal,
                workspaceId,
                projectId,
                new CreateRequirementRequest("   ", "desc", "acceptance", null)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> assertThat(((WorkspaceProjectException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("createRequirement fails if project not found")
    void createRequirementProjectNotFound() {
        when(jdbcTemplate.queryForObject(contains("FROM projects WHERE id = ?"), eq(Integer.class), eq(projectId), eq(workspaceId)))
                .thenReturn(0);

        assertThatThrownBy(() -> requirementService.createRequirement(
                pmPrincipal,
                workspaceId,
                projectId,
                new CreateRequirementRequest("Req Title", "desc", "acceptance", null)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> assertThat(((WorkspaceProjectException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("createRequirement succeeds and generates REQ-1")
    void createRequirementSuccess() {
        when(jdbcTemplate.queryForObject(contains("FROM projects WHERE id = ?"), eq(Integer.class), eq(projectId), eq(workspaceId)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("code LIKE 'REQ-%'"), eq(Integer.class), eq(projectId)))
                .thenReturn(1);

        RequirementResponse response = requirementService.createRequirement(
                pmPrincipal,
                workspaceId,
                projectId,
                new CreateRequirementRequest("Req Title", "desc", "acceptance summary", null)
        );

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo("REQ-1");
        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(response.currentVersion()).isEqualTo(1);
        assertThat(response.versions()).hasSize(1);
        assertThat(response.versions().get(0).versionNo()).isEqualTo(1);

        verify(accessPolicy).requireProjectRole(pmPrincipal, workspaceId, projectId, ProjectRole.PM);
        verify(jdbcTemplate).update(contains("INSERT INTO requirements"), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(jdbcTemplate).update(contains("INSERT INTO requirement_versions"), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("getRequirement returns requirement with version history")
    void getRequirementSuccess() {
        UUID reqId = UUID.randomUUID();
        when(jdbcTemplate.query(contains("FROM requirements r"), any(RowMapper.class), eq(reqId), eq(projectId)))
                .thenReturn(List.of(new RequirementResponse(
                        reqId, projectId, "REQ-1", "REQUIREMENT", null, "Title", "Desc",
                        "DRAFT", 1, userId, null, "Summary", null, null,
                        Instant.now(), Instant.now(), 0L
                )));

        RequirementResponse response = requirementService.getRequirement(pmPrincipal, workspaceId, projectId, reqId);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(reqId);
        verify(accessPolicy).requireProjectMember(pmPrincipal, workspaceId, projectId);
    }

    @Test
    @DisplayName("updateRequirement fails on stale version with 409 PLAN_STALE")
    void updateRequirementStaleVersion() {
        UUID reqId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(contains("FROM requirements"), eq(reqId), eq(projectId)))
                .thenReturn(List.of(Map.of(
                        "id", reqId,
                        "title", "Old Title",
                        "description", "Old Desc",
                        "current_version", 1,
                        "status", "DRAFT",
                        "version", 5L
                )));

        assertThatThrownBy(() -> requirementService.updateRequirement(
                pmPrincipal,
                workspaceId,
                projectId,
                reqId,
                new UpdateRequirementRequest("New Title", "New Desc", "New Acc", "Reason", 3L)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("PLAN_STALE");
                });
    }

    @Test
    @DisplayName("updateRequirement appends new version v2 and leaves v1 intact")
    void updateRequirementSuccess() {
        UUID reqId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(contains("FROM requirements"), eq(reqId), eq(projectId)))
                .thenReturn(List.of(Map.of(
                        "id", reqId,
                        "title", "Old Title",
                        "description", "Old Desc",
                        "current_version", 1,
                        "status", "APPROVED",
                        "version", 1L
                )));

        when(jdbcTemplate.query(contains("FROM requirements r"), any(RowMapper.class), eq(reqId), eq(projectId)))
                .thenReturn(List.of(new RequirementResponse(
                        reqId, projectId, "REQ-1", "REQUIREMENT", null, "New Title", "New Desc",
                        "DRAFT", 2, userId, null, "New Acc", null, null,
                        Instant.now(), Instant.now(), 2L
                )));

        RequirementResponse response = requirementService.updateRequirement(
                pmPrincipal,
                workspaceId,
                projectId,
                reqId,
                new UpdateRequirementRequest("New Title", "New Desc", "New Acc", "Scope change", 1L)
        );

        assertThat(response).isNotNull();
        verify(accessPolicy).requireProjectRole(pmPrincipal, workspaceId, projectId, ProjectRole.PM);
        verify(jdbcTemplate).update(contains("INSERT INTO requirement_versions"), any(), eq(projectId), eq(reqId), eq(2), eq("New Desc"), eq("New Acc"), eq("Scope change"), any());
        verify(jdbcTemplate).update(contains("UPDATE requirements"), eq("New Title"), eq("New Desc"), eq(2), any(), eq(reqId));
    }

    @Test
    @DisplayName("approveRequirement sets approved_at on current version and status APPROVED on requirement")
    void approveRequirementSuccess() {
        UUID reqId = UUID.randomUUID();
        when(jdbcTemplate.query(contains("SELECT current_version FROM requirements"), any(RowMapper.class), eq(reqId), eq(projectId)))
                .thenReturn(List.of(2));

        when(jdbcTemplate.query(contains("FROM requirements r"), any(RowMapper.class), eq(reqId), eq(projectId)))
                .thenReturn(List.of(new RequirementResponse(
                        reqId, projectId, "REQ-1", "REQUIREMENT", null, "Title", "Desc",
                        "APPROVED", 2, userId, userId, "Summary", Instant.now(), null,
                        Instant.now(), Instant.now(), 2L
                )));

        RequirementResponse response = requirementService.approveRequirement(pmPrincipal, workspaceId, projectId, reqId);

        assertThat(response).isNotNull();
        verify(accessPolicy).requireProjectRole(pmPrincipal, workspaceId, projectId, ProjectRole.PM);
        verify(jdbcTemplate).update(contains("UPDATE requirement_versions SET approved_at = ?"), any(), eq(reqId), eq(2));
        verify(jdbcTemplate).update(contains("UPDATE requirements SET status = 'APPROVED'"), eq(userId), any(), eq(reqId));
    }
}
