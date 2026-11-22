package com.project.devsphere.requirement.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.requirement.api.dto.ChangeRequestDecisionRequest;
import com.project.devsphere.requirement.api.dto.ChangeRequestResponse;
import com.project.devsphere.requirement.api.dto.CreateChangeRequest;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChangeRequestServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AccessPolicy accessPolicy;

    private ChangeRequestService changeRequestService;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private SessionService.Principal clientPrincipal;
    private SessionService.Principal pmPrincipal;

    @BeforeEach
    void setUp() {
        changeRequestService = new ChangeRequestService(jdbcTemplate, accessPolicy);
        clientPrincipal = new SessionService.Principal(userId, sessionId, "Client User", "USER");
        pmPrincipal = new SessionService.Principal(UUID.randomUUID(), sessionId, "PM User", "USER");
    }

    @Test
    @DisplayName("submitChangeRequest fails if title is blank")
    void submitChangeRequestBlankTitle() {
        assertThatThrownBy(() -> changeRequestService.submitChangeRequest(
                clientPrincipal,
                workspaceId,
                projectId,
                new CreateChangeRequest("   ", "desc", null)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> assertThat(((WorkspaceProjectException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("submitChangeRequest fails if target requirement does not exist in project")
    void submitChangeRequestTargetNotFound() {
        UUID targetId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(contains("FROM projects WHERE id = ?"), eq(Integer.class), eq(projectId), eq(workspaceId)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("FROM requirements WHERE id = ?"), eq(Integer.class), eq(targetId), eq(projectId)))
                .thenReturn(0);

        assertThatThrownBy(() -> changeRequestService.submitChangeRequest(
                clientPrincipal,
                workspaceId,
                projectId,
                new CreateChangeRequest("CR Title", "desc", targetId)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> assertThat(((WorkspaceProjectException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("submitChangeRequest succeeds and creates CR with PENDING status")
    void submitChangeRequestSuccess() {
        when(jdbcTemplate.queryForObject(contains("FROM projects WHERE id = ?"), eq(Integer.class), eq(projectId), eq(workspaceId)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("code LIKE 'CR-%'"), eq(Integer.class), eq(projectId)))
                .thenReturn(1);

        ChangeRequestResponse response = changeRequestService.submitChangeRequest(
                clientPrincipal,
                workspaceId,
                projectId,
                new CreateChangeRequest("CR Title", "Detailed scope change", null)
        );

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo("CR-1");
        assertThat(response.changeRequestStatus()).isEqualTo("PENDING");
        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(response.requestedBy()).isEqualTo(userId);

        verify(accessPolicy).requireAnyProjectRole(clientPrincipal, workspaceId, projectId, Set.of(ProjectRole.CLIENT, ProjectRole.PM));
        verify(jdbcTemplate).update(contains("INSERT INTO requirements"), any(), any(), eq("CR-1"), any(), eq("CR Title"), eq("Detailed scope change"), eq(userId), any(), any());
        verify(jdbcTemplate).update(contains("INSERT INTO requirement_versions"), any(), any(), any(), eq("Detailed scope change"), any());
    }

    @Test
    @DisplayName("listChangeRequests: PM sees all change requests in project")
    void listChangeRequestsAsPm() {
        when(accessPolicy.requireProjectMember(pmPrincipal, workspaceId, projectId))
                .thenReturn(new ProjectScope(workspaceId, projectId, pmPrincipal.userId(), Set.of(ProjectRole.PM)));

        changeRequestService.listChangeRequests(pmPrincipal, workspaceId, projectId);

        verify(jdbcTemplate).query(contains("WHERE r.project_id = ? AND r.kind = 'CHANGE_REQUEST'"), any(RowMapper.class), eq(projectId));
    }

    @Test
    @DisplayName("listChangeRequests: CLIENT sees only their own change requests")
    void listChangeRequestsAsClient() {
        when(accessPolicy.requireProjectMember(clientPrincipal, workspaceId, projectId))
                .thenReturn(new ProjectScope(workspaceId, projectId, clientPrincipal.userId(), Set.of(ProjectRole.CLIENT)));

        changeRequestService.listChangeRequests(clientPrincipal, workspaceId, projectId);

        verify(jdbcTemplate).query(contains("AND r.requested_by = ?"), any(RowMapper.class), eq(projectId), eq(userId));
    }

    @Test
    @DisplayName("decideChangeRequest fails if decision is invalid")
    void decideChangeRequestInvalidDecision() {
        UUID crId = UUID.randomUUID();

        assertThatThrownBy(() -> changeRequestService.decideChangeRequest(
                pmPrincipal,
                workspaceId,
                projectId,
                crId,
                new ChangeRequestDecisionRequest("INVALID_DECISION", "Reason", null)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> assertThat(((WorkspaceProjectException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("decideChangeRequest fails if CR was already decided")
    void decideChangeRequestAlreadyDecided() {
        UUID crId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(contains("FROM requirements"), eq(crId), eq(projectId)))
                .thenReturn(List.of(Map.of(
                        "id", crId,
                        "code", "CR-1",
                        "title", "Title",
                        "description", "Desc",
                        "parent_requirement_id", UUID.randomUUID(),
                        "requested_by", userId,
                        "status", "APPROVED",
                        "change_request_status", "APPROVED",
                        "version", 1L
                )));

        assertThatThrownBy(() -> changeRequestService.decideChangeRequest(
                pmPrincipal,
                workspaceId,
                projectId,
                crId,
                new ChangeRequestDecisionRequest("REJECTED", "Rejection reason", null)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> assertThat(((WorkspaceProjectException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("decideChangeRequest on APPROVED creates draft plan_proposal (TC-33)")
    void decideChangeRequestApprovedCreatesDraftPlanProposal() {
        UUID crId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(contains("FROM requirements"), eq(crId), eq(projectId)))
                .thenReturn(List.of(Map.of(
                        "id", crId,
                        "code", "CR-1",
                        "title", "Title",
                        "description", "Desc",
                        "parent_requirement_id", UUID.randomUUID(),
                        "requested_by", userId,
                        "status", "DRAFT",
                        "change_request_status", "PENDING",
                        "version", 0L,
                        "created_at", Timestamp.from(Instant.now())
                )));

        when(jdbcTemplate.queryForObject(contains("SELECT version FROM projects WHERE id = ?"), eq(Long.class), eq(projectId)))
                .thenReturn(3L);

        ChangeRequestResponse response = changeRequestService.decideChangeRequest(
                pmPrincipal,
                workspaceId,
                projectId,
                crId,
                new ChangeRequestDecisionRequest("APPROVED", "Scope change approved for replan", 0L)
        );

        assertThat(response).isNotNull();
        assertThat(response.changeRequestStatus()).isEqualTo("APPROVED");
        assertThat(response.proposalId()).isNotNull();

        verify(accessPolicy).requireProjectRole(pmPrincipal, workspaceId, projectId, ProjectRole.PM);
        verify(jdbcTemplate).update(contains("UPDATE requirements"), eq("APPROVED"), eq("APPROVED"), eq(pmPrincipal.userId()), anyString(), any(), eq(crId));
        // TC-33: Inserts draft plan_proposals without altering project baseline
        verify(jdbcTemplate).update(contains("INSERT INTO plan_proposals"), eq(response.proposalId()), eq(projectId), eq(3L), eq(crId), eq(pmPrincipal.userId()), any(), any());
    }

    @Test
    @DisplayName("decideChangeRequest on REJECTED updates status and does NOT create plan_proposal")
    void decideChangeRequestRejectedDoesNotCreateProposal() {
        UUID crId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(contains("FROM requirements"), eq(crId), eq(projectId)))
                .thenReturn(List.of(Map.of(
                        "id", crId,
                        "code", "CR-1",
                        "title", "Title",
                        "description", "Desc",
                        "parent_requirement_id", UUID.randomUUID(),
                        "requested_by", userId,
                        "status", "DRAFT",
                        "change_request_status", "PENDING",
                        "version", 0L,
                        "created_at", Timestamp.from(Instant.now())
                )));

        ChangeRequestResponse response = changeRequestService.decideChangeRequest(
                pmPrincipal,
                workspaceId,
                projectId,
                crId,
                new ChangeRequestDecisionRequest("REJECTED", "Out of scope", 0L)
        );

        assertThat(response).isNotNull();
        assertThat(response.changeRequestStatus()).isEqualTo("REJECTED");
        assertThat(response.proposalId()).isNull();

        verify(jdbcTemplate, never()).update(contains("INSERT INTO plan_proposals"), any(), any(), any(), any(), any(), any(), any());
    }
}
