package com.project.devsphere.project.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
class PmTransferServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AccessPolicy accessPolicy;

    private PmTransferService pmTransferService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID candidateId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private SessionService.Principal ownerPrincipal;
    private SessionService.Principal candidatePrincipal;

    @BeforeEach
    void setUp() {
        pmTransferService = new PmTransferService(jdbcTemplate, accessPolicy);
        ownerPrincipal = new SessionService.Principal(ownerId, sessionId, "Owner User", "USER");
        candidatePrincipal = new SessionService.Principal(candidateId, sessionId, "Candidate User", "USER");
    }

    @Test
    @DisplayName("proposePmAssignment fails if command is missing expected fields")
    void proposePmAssignmentMissingFields() {
        assertThatThrownBy(() -> pmTransferService.proposePmAssignment(
                ownerPrincipal,
                workspaceId,
                projectId,
                new ProposePmCommand(null, 0L)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> assertThat(((WorkspaceProjectException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("proposePmAssignment translates Project version conflict to PLAN_STALE")
    void proposePmAssignmentStaleVersion() {
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new DataIntegrityViolationException("ERROR: Project version conflict"));

        assertThatThrownBy(() -> pmTransferService.proposePmAssignment(
                ownerPrincipal,
                workspaceId,
                projectId,
                new ProposePmCommand(candidateId, 0L)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("PLAN_STALE");
                });
    }

    @Test
    @DisplayName("proposePmAssignment translates already pending assignment to DUPLICATE_RESOURCE")
    void proposePmAssignmentAlreadyPending() {
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new DataIntegrityViolationException("ERROR: A PM assignment is already pending"));

        assertThatThrownBy(() -> pmTransferService.proposePmAssignment(
                ownerPrincipal,
                workspaceId,
                projectId,
                new ProposePmCommand(candidateId, 0L)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("DUPLICATE_RESOURCE");
                });
    }

    @Test
    @DisplayName("getCurrentPmAssignment returns empty when no assignment is configured")
    void getCurrentPmAssignmentEmpty() {
        when(jdbcTemplate.queryForList(anyString(), eq(projectId), eq(workspaceId)))
                .thenReturn(Collections.emptyList());

        Optional<PmAssignmentDetails> result = pmTransferService.getCurrentPmAssignment(
                ownerPrincipal,
                workspaceId,
                projectId
        );

        assertThat(result).isEmpty();
        verify(accessPolicy).requireWorkspaceMember(ownerPrincipal, workspaceId);
    }

    @Test
    @DisplayName("acceptPmAssignment rejects caller who is not the candidate with 403 ACCESS_DENIED")
    void acceptPmAssignmentImposter() {
        UUID imposterId = UUID.randomUUID();
        SessionService.Principal imposterPrincipal = new SessionService.Principal(imposterId, sessionId, "Imposter", "USER");

        when(jdbcTemplate.query(contains("settings#>>'{pmAssignment,candidateUserId}'"), any(org.springframework.jdbc.core.RowMapper.class), eq(projectId), eq(workspaceId)))
                .thenReturn(List.of(candidateId.toString()));

        assertThatThrownBy(() -> pmTransferService.acceptPmAssignment(
                imposterPrincipal,
                workspaceId,
                projectId,
                UUID.randomUUID(),
                new AcceptPmCommand(0L)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(wpe.getCode()).isEqualTo("ACCESS_DENIED");
                });
    }

    @Test
    @DisplayName("acceptPmAssignment translates invalid/expired to PM_TRANSFER_NOT_ACCEPTED")
    void acceptPmAssignmentInvalidExpired() {
        when(jdbcTemplate.query(contains("settings#>>'{pmAssignment,candidateUserId}'"), any(org.springframework.jdbc.core.RowMapper.class), eq(projectId), eq(workspaceId)))
                .thenReturn(List.of(candidateId.toString()));

        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new DataIntegrityViolationException("ERROR: PM invitation is invalid or expired"));

        assertThatThrownBy(() -> pmTransferService.acceptPmAssignment(
                candidatePrincipal,
                workspaceId,
                projectId,
                UUID.randomUUID(),
                new AcceptPmCommand(0L)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("PM_TRANSFER_NOT_ACCEPTED");
                });
    }

    @Test
    @DisplayName("acceptPmAssignment activates workspace if SETUP_INCOMPLETE")
    void acceptPmAssignmentActivatesWorkspace() {
        when(jdbcTemplate.query(contains("settings#>>'{pmAssignment,candidateUserId}'"), any(org.springframework.jdbc.core.RowMapper.class), eq(projectId), eq(workspaceId)))
                .thenReturn(List.of(candidateId.toString()));

        pmTransferService.acceptPmAssignment(
                candidatePrincipal,
                workspaceId,
                projectId,
                UUID.randomUUID(),
                new AcceptPmCommand(0L)
        );

        verify(accessPolicy).requireAuthenticatedUser(candidatePrincipal);
        verify(jdbcTemplate).update(
                contains("UPDATE workspaces SET status = 'ACTIVE'"),
                eq(workspaceId)
        );
    }
}
