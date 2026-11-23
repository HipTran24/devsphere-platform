package com.project.devsphere.project.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.project.domain.Project;
import com.project.devsphere.project.domain.ProjectMember;
import com.project.devsphere.project.domain.ProjectMemberRole;
import com.project.devsphere.project.infrastructure.persistence.ProjectMemberRepository;
import com.project.devsphere.project.infrastructure.persistence.ProjectMemberRoleRepository;
import com.project.devsphere.project.infrastructure.persistence.ProjectRepository;
import com.project.devsphere.workitem.infrastructure.persistence.WorkItemRepository;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import com.project.devsphere.workspace.domain.Workspace;
import com.project.devsphere.workspace.infrastructure.persistence.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private ProjectMemberRoleRepository projectMemberRoleRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkItemRepository workItemRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private JdbcTemplate jdbcTemplate;

    private ProjectService projectService;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private SessionService.Principal principal;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectRepository,
                projectMemberRepository,
                projectMemberRoleRepository,
                workspaceRepository,
                workItemRepository,
                accessPolicy,
                jdbcTemplate
        );
        principal = new SessionService.Principal(userId, sessionId, "Project Lead", "USER");
    }

    @Test
    void createProject_selfAssignPm_success() {
        CreateProjectCommand command = new CreateProjectCommand(
                "DEV",
                "Dev Project",
                "Description",
                "Objective",
                "Asia/Ho_Chi_Minh",
                LocalDate.now(),
                LocalDate.now().plusMonths(3),
                14,
                true
        );

        when(projectRepository.existsByWorkspaceId(workspaceId)).thenReturn(false);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(projectId);
            return p;
        });

        when(projectMemberRepository.save(any(ProjectMember.class))).thenAnswer(inv -> {
            ProjectMember m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });

        Workspace ws = new Workspace();
        ws.setId(workspaceId);
        ws.setStatus(Workspace.Status.SETUP_INCOMPLETE);
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(ws));

        Project created = projectService.createProject(principal, workspaceId, command);

        assertThat(created).isNotNull();
        assertThat(created.getProjectKey()).isEqualTo("DEV");
        assertThat(created.getStatus()).isEqualTo(Project.Status.PLANNING);
        assertThat(ws.getStatus()).isEqualTo(Workspace.Status.ACTIVE);

        verify(accessPolicy).requireWorkspaceOwner(principal, workspaceId);
        verify(projectMemberRoleRepository).save(any(ProjectMemberRole.class));
        verify(workspaceRepository).save(ws);
    }

    @Test
    void createProject_duplicateProjectInWorkspace_throwsConflict() {
        CreateProjectCommand command = new CreateProjectCommand(
                "DEV", "Dev", null, null, null, null, null, 14, false
        );
        when(projectRepository.existsByWorkspaceId(workspaceId)).thenReturn(true);

        assertThatThrownBy(() -> projectService.createProject(principal, workspaceId, command))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("DUPLICATE_RESOURCE");
                });
    }

    @Test
    void createProject_invalidKey_throwsBadRequest() {
        CreateProjectCommand command = new CreateProjectCommand(
                "invalid_key", "Dev", null, null, null, null, null, 14, false
        );

        assertThatThrownBy(() -> projectService.createProject(principal, workspaceId, command))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(wpe.getCode()).isEqualTo("VALIDATION_FAILED");
                });
    }

    @Test
    void listProjects_scopesToWorkspace() {
        Project p = new Project();
        p.setId(projectId);
        p.setWorkspaceId(workspaceId);
        p.setName("Project List");
        when(projectRepository.findByWorkspaceId(workspaceId)).thenReturn(Optional.of(p));

        List<Project> result = projectService.listProjects(principal, workspaceId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Project List");
        verify(accessPolicy).requireWorkspaceMember(principal, workspaceId);
    }

    @Test
    void getProject_crossTenantMismatch_throwsAccessDenied() {
        UUID otherWorkspaceId = UUID.randomUUID();
        Project p = new Project();
        p.setId(projectId);
        p.setWorkspaceId(otherWorkspaceId);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> projectService.getProject(principal, workspaceId, projectId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void updateProject_attemptingCommittedDeadlineChange_throwsConflict() {
        Project p = new Project();
        p.setId(projectId);
        p.setWorkspaceId(workspaceId);
        p.setCommittedDeadline(LocalDate.of(2026, 12, 31));
        p.setVersion(1L);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));

        UpdateProjectCommand command = new UpdateProjectCommand(
                "New Name", null, null, null, null, LocalDate.of(2027, 1, 15), 14, 1L
        );

        assertThatThrownBy(() -> projectService.updateProject(principal, workspaceId, projectId, command))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("CLIENT_DEADLINE_CONFIRMATION_REQUIRED");
                });

        verify(accessPolicy).requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);
    }

    @Test
    void updateProject_optimisticLockMismatch_throwsPlanStale() {
        Project p = new Project();
        p.setId(projectId);
        p.setWorkspaceId(workspaceId);
        p.setVersion(3L);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));

        UpdateProjectCommand command = new UpdateProjectCommand(
                "New Name", null, null, null, null, null, 14, 2L
        );

        assertThatThrownBy(() -> projectService.updateProject(principal, workspaceId, projectId, command))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("PLAN_STALE");
                });
    }

    @Test
    void activateProject_missingObjective_throwsUnprocessable() {
        Project p = new Project();
        p.setId(projectId);
        p.setWorkspaceId(workspaceId);
        p.setStatus(Project.Status.PLANNING);
        p.setObjective(null); // Missing
        p.setPlannedStart(LocalDate.now());

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> projectService.activateProject(principal, workspaceId, projectId, new ActivateProjectCommand(null)))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(wpe.getCode()).isEqualTo("ACTIVATION_PRECONDITION_FAILED");
                });
    }

    @Test
    void activateProject_emptyBacklog_throwsUnprocessable() {
        Project p = new Project();
        p.setId(projectId);
        p.setWorkspaceId(workspaceId);
        p.setStatus(Project.Status.PLANNING);
        p.setObjective("Deliver Platform");
        p.setPlannedStart(LocalDate.now());

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));
        when(workItemRepository.countByProjectIdAndDeletedAtIsNull(projectId)).thenReturn(0L);

        assertThatThrownBy(() -> projectService.activateProject(principal, workspaceId, projectId, new ActivateProjectCommand(null)))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(wpe.getCode()).isEqualTo("ACTIVATION_PRECONDITION_FAILED");
                });
    }

    @Test
    void activateProject_success() {
        Project p = new Project();
        p.setId(projectId);
        p.setWorkspaceId(workspaceId);
        p.setStatus(Project.Status.PLANNING);
        p.setObjective("Deliver Platform");
        p.setPlannedStart(LocalDate.now());
        p.setTimezone("Asia/Ho_Chi_Minh");

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));
        when(workItemRepository.countByProjectIdAndDeletedAtIsNull(projectId)).thenReturn(5L);
        when(jdbcTemplate.queryForObject(contains("ds_has_project_role"), eq(Integer.class), eq(projectId), eq(projectId)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("pg_timezone_names"), eq(Integer.class), eq("Asia/Ho_Chi_Minh")))
                .thenReturn(1);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project activated = projectService.activateProject(principal, workspaceId, projectId, new ActivateProjectCommand(null));

        assertThat(activated.getStatus()).isEqualTo(Project.Status.ACTIVE);
        verify(accessPolicy).requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);
    }

    @Test
    void updateProject_withDeadlineApproverId_clientRoleVerified_success() {
        UUID approverId = UUID.randomUUID();
        Project p = new Project();
        p.setId(projectId);
        p.setWorkspaceId(workspaceId);
        p.setStatus(Project.Status.PLANNING);
        p.setName("Initial Name");
        p.setVersion(0L);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));
        when(jdbcTemplate.queryForObject(
                eq("SELECT ds_has_project_role(?, ?, 'CLIENT')"),
                eq(Boolean.class),
                eq(projectId),
                eq(approverId)
        )).thenReturn(true);
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProjectCommand command = new UpdateProjectCommand(
                "Updated Name",
                null,
                null,
                null,
                null,
                null,
                14,
                approverId,
                0L
        );

        Project updated = projectService.updateProject(principal, workspaceId, projectId, command);

        assertThat(updated.getDeadlineApproverId()).isEqualTo(approverId);
        assertThat(updated.getName()).isEqualTo("Updated Name");
    }

    @Test
    void updateProject_withDeadlineApproverId_notClient_throwsValidationFailed() {
        UUID approverId = UUID.randomUUID();
        Project p = new Project();
        p.setId(projectId);
        p.setWorkspaceId(workspaceId);
        p.setStatus(Project.Status.PLANNING);
        p.setVersion(0L);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(p));
        when(jdbcTemplate.queryForObject(
                eq("SELECT ds_has_project_role(?, ?, 'CLIENT')"),
                eq(Boolean.class),
                eq(projectId),
                eq(approverId)
        )).thenReturn(false);

        UpdateProjectCommand command = new UpdateProjectCommand(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                approverId,
                0L
        );

        assertThatThrownBy(() -> projectService.updateProject(principal, workspaceId, projectId, command))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(wpe.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(wpe.getMessage()).contains("Designated deadline approver must be an active CLIENT member of the project");
                });
    }
}
