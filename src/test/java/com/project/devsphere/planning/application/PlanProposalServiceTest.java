package com.project.devsphere.planning.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.planning.api.dto.CreatePlanProposalRequest;
import com.project.devsphere.planning.api.dto.PlanProposalResponse;
import com.project.devsphere.planning.api.dto.SolveProposalRequest;
import com.project.devsphere.planning.api.dto.SolverDiagnosticsResponse;
import com.project.devsphere.planning.domain.PlanProposal;
import com.project.devsphere.planning.infrastructure.persistence.PlanProposalRepository;
import com.project.devsphere.resource.api.ResourceCapacitySnapshot;
import com.project.devsphere.resource.api.ResourceCapacitySnapshotReader;
import com.project.devsphere.solver.application.SchedulingSolver;
import com.project.devsphere.solver.domain.SolverInputSnapshot;
import com.project.devsphere.solver.domain.SolverResult;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlanProposalServiceTest {

    @Mock private PlanProposalRepository proposalRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private ResourceCapacitySnapshotReader capacitySnapshotReader;
    @Mock private SchedulingSolver schedulingSolver;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PlanProposalService service;

    private final SessionService.Principal pmPrincipal = new SessionService.Principal(UUID.randomUUID(), UUID.randomUUID(), "pm@example.test", "USER");
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PlanProposalService(
                proposalRepository,
                accessPolicy,
                capacitySnapshotReader,
                schedulingSolver,
                jdbcTemplate,
                entityManager
        );
    }

    @Test
    @DisplayName("createDraftProposal creates proposal in DRAFT status when project version matches")
    void createDraftProposalSuccess() {
        when(jdbcTemplate.queryForObject(eq("SELECT version FROM projects WHERE id = ?"), eq(Long.class), eq(projectId)))
                .thenReturn(3L);

        when(proposalRepository.save(any(PlanProposal.class))).thenAnswer(invocation -> {
            PlanProposal p = invocation.getArgument(0);
            p.setId(UUID.randomUUID());
            p.setVersion(0L);
            return p;
        });

        CreatePlanProposalRequest req = new CreatePlanProposalRequest(
                PlanProposal.Kind.MANUAL_REPLAN,
                3L,
                null,
                null,
                null,
                Map.of("schemaVersion", 1, "items", List.of(Map.of("tempKey", "T1", "estimateMinutes", 120))),
                List.of("Assumption 1"),
                false,
                null
        );

        PlanProposalResponse res = service.createDraftProposal(pmPrincipal, workspaceId, projectId, req);

        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo(PlanProposal.Status.DRAFT);
        assertThat(res.solverStatus()).isEqualTo(PlanProposal.SolverStatus.NOT_RUN);
        assertThat(res.baseProjectVersion()).isEqualTo(3L);
        assertThat(res.kind()).isEqualTo(PlanProposal.Kind.MANUAL_REPLAN);

        verify(accessPolicy).requireProjectRole(pmPrincipal, workspaceId, projectId, ProjectRole.PM);
        verify(proposalRepository).save(any(PlanProposal.class));
    }

    @Test
    @DisplayName("createDraftProposal rejects with 409 PLAN_STALE when expectedProjectVersion mismatches")
    void createDraftProposalRejectsStaleProjectVersion() {
        when(jdbcTemplate.queryForObject(eq("SELECT version FROM projects WHERE id = ?"), eq(Long.class), eq(projectId)))
                .thenReturn(5L);

        CreatePlanProposalRequest req = new CreatePlanProposalRequest(
                PlanProposal.Kind.MANUAL_REPLAN,
                4L, // Stale!
                null,
                null,
                null,
                Map.of(),
                List.of(),
                false,
                null
        );

        assertThatThrownBy(() -> service.createDraftProposal(pmPrincipal, workspaceId, projectId, req))
                .isInstanceOf(PlanProposalException.class)
                .hasMessageContaining("Project version is stale");
    }

    @Test
    @DisplayName("solveProposal executes two-phase update and returns solved proposal")
    void solveProposalSuccess() {
        UUID proposalId = UUID.randomUUID();

        when(jdbcTemplate.queryForObject(eq("SELECT version FROM projects WHERE id = ?"), eq(Long.class), eq(projectId)))
                .thenReturn(2L);
        when(jdbcTemplate.query(eq("SELECT timezone FROM projects WHERE id = ?"), any(RowMapper.class), eq(projectId)))
                .thenReturn(List.of("UTC"));

        PlanProposal existing = new PlanProposal();
        existing.setId(proposalId);
        existing.setProjectId(projectId);
        existing.setVersion(1L);
        existing.setStatus(PlanProposal.Status.DRAFT);
        existing.setSolverStatus(PlanProposal.SolverStatus.NOT_RUN);
        existing.setProposalPayload(Map.of(
                "schemaVersion", 1,
                "items", List.of(Map.of("tempKey", "T1", "estimateMinutes", 60))
        ));

        when(proposalRepository.findByIdAndProjectId(proposalId, projectId))
                .thenReturn(Optional.of(existing));

        when(capacitySnapshotReader.read(eq(projectId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new ResourceCapacitySnapshot(projectId, workspaceId, 2L, "UTC", Instant.now(), List.of()));

        SolverResult solverResult = SolverResult.emptySuccess();
        when(schedulingSolver.solve(any(SolverInputSnapshot.class))).thenReturn(solverResult);

        SolveProposalRequest solveReq = new SolveProposalRequest(
                2L,
                1L,
                10,
                LocalDate.now(),
                LocalDate.now().plusDays(30)
        );

        PlanProposalResponse res = service.solveProposal(pmPrincipal, workspaceId, projectId, proposalId, solveReq);

        assertThat(res).isNotNull();
        // Verify Phase 1 update: update payload, solver_details, assumptions
        verify(jdbcTemplate).update(contains("SET proposal_payload = ?::jsonb"), anyString(), anyString(), anyString(), eq(proposalId), eq(projectId));
        // Verify Phase 2 update: update solver_status = OPTIMAL
        verify(jdbcTemplate).update(contains("SET solver_status = ?"), eq("OPTIMAL"), eq(proposalId), eq(projectId));
        // Verify entityManager.clear() was invoked
        verify(entityManager).clear();
    }

    @Test
    @DisplayName("solveProposal rejects with 409 PROPOSAL_VERSION_CONFLICT when proposal version mismatches")
    void solveProposalRejectsStaleProposalVersion() {
        UUID proposalId = UUID.randomUUID();

        when(jdbcTemplate.queryForObject(eq("SELECT version FROM projects WHERE id = ?"), eq(Long.class), eq(projectId)))
                .thenReturn(2L);

        PlanProposal existing = new PlanProposal();
        existing.setId(proposalId);
        existing.setProjectId(projectId);
        existing.setVersion(3L);

        when(proposalRepository.findByIdAndProjectId(proposalId, projectId))
                .thenReturn(Optional.of(existing));

        SolveProposalRequest solveReq = new SolveProposalRequest(
                2L,
                2L, // Expected 2, but actual is 3!
                10,
                null,
                null
        );

        assertThatThrownBy(() -> service.solveProposal(pmPrincipal, workspaceId, projectId, proposalId, solveReq))
                .isInstanceOf(PlanProposalException.class)
                .hasMessageContaining("Proposal version is stale");
    }

    @Test
    @DisplayName("getProposal returns proposal when found")
    void getProposalSuccess() {
        UUID proposalId = UUID.randomUUID();
        PlanProposal existing = new PlanProposal();
        existing.setId(proposalId);
        existing.setProjectId(projectId);
        existing.setVersion(0L);
        existing.setStatus(PlanProposal.Status.DRAFT);
        existing.setSolverStatus(PlanProposal.SolverStatus.NOT_RUN);
        existing.setProposalPayload(Map.of("schemaVersion", 1, "items", List.of()));
        existing.setSolverDetails(Map.of("schemaVersion", 1));
        existing.setAssumptions(List.of());

        when(proposalRepository.findByIdAndProjectId(proposalId, projectId))
                .thenReturn(Optional.of(existing));

        PlanProposalResponse res = service.getProposal(pmPrincipal, workspaceId, projectId, proposalId);

        assertThat(res).isNotNull();
        assertThat(res.id()).isEqualTo(proposalId);
    }

    @Test
    @DisplayName("getProposal throws 404 when proposal not found")
    void getProposalNotFound() {
        UUID proposalId = UUID.randomUUID();
        when(proposalRepository.findByIdAndProjectId(proposalId, projectId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProposal(pmPrincipal, workspaceId, projectId, proposalId))
                .isInstanceOf(PlanProposalException.class)
                .hasMessageContaining("Plan proposal not found");
    }

    @Test
    @DisplayName("getDiagnostics returns solver diagnostics")
    void getDiagnosticsSuccess() {
        UUID proposalId = UUID.randomUUID();
        PlanProposal existing = new PlanProposal();
        existing.setId(proposalId);
        existing.setProjectId(projectId);
        existing.setVersion(1L);
        existing.setSolverStatus(PlanProposal.SolverStatus.OPTIMAL);
        existing.setSolverDetails(Map.of(
                "schemaVersion", 1,
                "rawNativeStatus", "OPTIMAL",
                "wallTimeSeconds", 1.25,
                "makespanMinutes", 480L
        ));

        when(proposalRepository.findByIdAndProjectId(proposalId, projectId))
                .thenReturn(Optional.of(existing));

        SolverDiagnosticsResponse diag = service.getDiagnostics(pmPrincipal, workspaceId, projectId, proposalId);

        assertThat(diag).isNotNull();
        assertThat(diag.solverStatus()).isEqualTo(PlanProposal.SolverStatus.OPTIMAL);
        assertThat(diag.rawNativeStatus()).isEqualTo("OPTIMAL");
        assertThat(diag.wallTimeSeconds()).isEqualTo(1.25);
        assertThat(diag.makespanMinutes()).isEqualTo(480L);
    }

    @Test
    @DisplayName("Non-PM caller is rejected with AccessDeniedException")
    void nonPmIsDenied() {
        doThrow(new AccessDeniedException("Access denied"))
                .when(accessPolicy).requireProjectRole(pmPrincipal, workspaceId, projectId, ProjectRole.PM);

        assertThatThrownBy(() -> service.listProposals(pmPrincipal, workspaceId, projectId))
                .isInstanceOf(AccessDeniedException.class);
    }
}
