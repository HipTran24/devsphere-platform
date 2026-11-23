package com.project.devsphere.clientportal.application;

import com.project.devsphere.clientportal.api.dto.ClientProjectOverviewResponse;
import com.project.devsphere.clientportal.api.dto.MilestoneReviewReceiptResponse;
import com.project.devsphere.clientportal.api.dto.PublishedMilestoneResponse;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.project.domain.Milestone;
import com.project.devsphere.project.infrastructure.persistence.MilestoneRepository;
import com.project.devsphere.reporting.infrastructure.persistence.ReportRepository;
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
import org.springframework.security.access.AccessDeniedException;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClientPortalServiceTest {

    @Mock
    private MilestoneRepository milestoneRepository;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private AccessPolicy accessPolicy;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ClientPortalService clientPortalService;

    private SessionService.Principal clientPrincipal;
    private UUID workspaceId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        clientPortalService = new ClientPortalService(milestoneRepository, reportRepository, accessPolicy, jdbcTemplate);
        clientPrincipal = new SessionService.Principal(UUID.randomUUID(), UUID.randomUUID(), "Client User", "USER");
        workspaceId = UUID.randomUUID();
        projectId = UUID.randomUUID();
    }

    @Test
    @DisplayName("getClientProjectOverview returns only published data with Zero Data Leak")
    void overviewReturnsPublishedDataZeroLeak() {
        ProjectScope scope = new ProjectScope(workspaceId, projectId, clientPrincipal.userId(), Set.of(ProjectRole.CLIENT));
        when(accessPolicy.requireProjectMember(clientPrincipal, workspaceId, projectId)).thenReturn(scope);

        when(jdbcTemplate.queryForMap(anyString(), eq(projectId), eq(workspaceId))).thenReturn(Map.of(
                "name", "Acme Mobile",
                "project_key", "ACME",
                "description", "Client visible mobile app",
                "objective", "Launch in Q4",
                "status", "ACTIVE",
                "planned_start", Date.valueOf(LocalDate.of(2026, 11, 1)),
                "committed_deadline", Date.valueOf(LocalDate.of(2026, 12, 31))
        ));

        when(jdbcTemplate.query(contains("FROM milestones"), any(RowMapper.class), eq(projectId))).thenReturn(List.of(
                new PublishedMilestoneResponse(
                        UUID.randomUUID(), projectId, "M1", "Beta Release", "Beta to testers",
                        LocalDate.of(2026, 11, 15), null, "READY_FOR_ACCEPTANCE", "NOT_SUBMITTED", true, null, null, 0L
                )
        ));

        when(jdbcTemplate.query(contains("FROM reports"), any(RowMapper.class), eq(projectId))).thenReturn(List.of());

        ClientProjectOverviewResponse overview = clientPortalService.getClientProjectOverview(clientPrincipal, workspaceId, projectId);

        assertThat(overview.projectName()).isEqualTo("Acme Mobile");
        assertThat(overview.publishedMilestonesCount()).isEqualTo(1);
        assertThat(overview.milestones()).hasSize(1);
        assertThat(overview.milestones().get(0).name()).isEqualTo("Beta Release");
    }

    @Test
    @DisplayName("reviewMilestone requires CLIENT role; throws AccessDeniedException when role is not CLIENT")
    void reviewMilestoneRejectsNonClient() {
        UUID milestoneId = UUID.randomUUID();
        doThrow(new AccessDeniedException("Access denied")).when(accessPolicy)
                .requireProjectRole(clientPrincipal, workspaceId, projectId, ProjectRole.CLIENT);

        assertThatThrownBy(() -> clientPortalService.reviewMilestone(clientPrincipal, workspaceId, projectId, milestoneId, "ACCEPTED", "Looks good"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("reviewMilestone throws notPublished when publishedToClient is false")
    void reviewMilestoneRejectsUnpublished() {
        UUID milestoneId = UUID.randomUUID();
        ProjectScope scope = new ProjectScope(workspaceId, projectId, clientPrincipal.userId(), Set.of(ProjectRole.CLIENT));
        when(accessPolicy.requireProjectRole(clientPrincipal, workspaceId, projectId, ProjectRole.CLIENT)).thenReturn(scope);

        Milestone milestone = new Milestone();
        milestone.setId(milestoneId);
        milestone.setProjectId(projectId);
        milestone.setPublishedToClient(false);
        when(milestoneRepository.findByIdAndProjectId(milestoneId, projectId)).thenReturn(Optional.of(milestone));

        assertThatThrownBy(() -> clientPortalService.reviewMilestone(clientPrincipal, workspaceId, projectId, milestoneId, "ACCEPTED", "Great job"))
                .isInstanceOf(ClientPortalException.class)
                .hasMessageContaining("not published to client");
    }

    @Test
    @DisplayName("reviewMilestone with ACCEPTED updates status and acceptanceStatus to ACCEPTED")
    void reviewMilestoneAcceptsSuccessfully() {
        UUID milestoneId = UUID.randomUUID();
        ProjectScope scope = new ProjectScope(workspaceId, projectId, clientPrincipal.userId(), Set.of(ProjectRole.CLIENT));
        when(accessPolicy.requireProjectRole(clientPrincipal, workspaceId, projectId, ProjectRole.CLIENT)).thenReturn(scope);

        Milestone milestone = new Milestone();
        milestone.setId(milestoneId);
        milestone.setProjectId(projectId);
        milestone.setPublishedToClient(true);
        milestone.setStatus(Milestone.Status.READY_FOR_ACCEPTANCE);
        milestone.setAcceptanceStatus(Milestone.AcceptanceStatus.PENDING_REVIEW);
        when(milestoneRepository.findByIdAndProjectId(milestoneId, projectId)).thenReturn(Optional.of(milestone));

        MilestoneReviewReceiptResponse receipt = clientPortalService.reviewMilestone(
                clientPrincipal, workspaceId, projectId, milestoneId, "ACCEPTED", "Verified by Client"
        );

        assertThat(receipt.decision()).isEqualTo("ACCEPTED");
        assertThat(receipt.status()).isEqualTo("ACCEPTED");
        assertThat(receipt.acceptanceStatus()).isEqualTo("ACCEPTED");
        assertThat(receipt.reviewedBy()).isEqualTo(clientPrincipal.userId());
        assertThat(receipt.reviewedAt()).isNotNull();

        assertThat(milestone.getStatus()).isEqualTo(Milestone.Status.ACCEPTED);
        assertThat(milestone.getAcceptanceStatus()).isEqualTo(Milestone.AcceptanceStatus.ACCEPTED);
        assertThat(milestone.getAcceptedBy()).isEqualTo(clientPrincipal.userId());
        assertThat(milestone.getAcceptedAt()).isNotNull();
    }
}
