package com.project.devsphere.project.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.project.api.dto.ActivateProjectRequest;
import com.project.devsphere.project.api.dto.CreateProjectRequest;
import com.project.devsphere.project.api.dto.ProjectResponse;
import com.project.devsphere.project.api.dto.UpdateProjectRequest;
import com.project.devsphere.project.domain.Project;
import com.project.devsphere.project.domain.ProjectMember;
import com.project.devsphere.project.domain.ProjectMemberRole;
import com.project.devsphere.project.infrastructure.persistence.ProjectMemberRoleRepository;
import com.project.devsphere.project.infrastructure.persistence.ProjectMemberRepository;
import com.project.devsphere.project.infrastructure.persistence.ProjectRepository;
import com.project.devsphere.workitem.infrastructure.persistence.WorkItemRepository;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import com.project.devsphere.workspace.domain.Workspace;
import com.project.devsphere.workspace.infrastructure.persistence.WorkspaceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class ProjectService {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]{1,15}$");

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectMemberRoleRepository projectMemberRoleRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkItemRepository workItemRepository;
    private final AccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectMemberRoleRepository projectMemberRoleRepository,
            WorkspaceRepository workspaceRepository,
            WorkItemRepository workItemRepository,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate) {
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository must not be null");
        this.projectMemberRepository = Objects.requireNonNull(projectMemberRepository, "projectMemberRepository must not be null");
        this.projectMemberRoleRepository = Objects.requireNonNull(projectMemberRoleRepository, "projectMemberRoleRepository must not be null");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository must not be null");
        this.workItemRepository = Objects.requireNonNull(workItemRepository, "workItemRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /**
     * Creates a project in a workspace.
     * Enforces exactly 1 project per workspace and workspace owner authority.
     * If selfAssignPm is true, activates workspace and assigns caller as PM.
     */
    public Project createProject(SessionService.Principal principal, UUID workspaceId, CreateProjectCommand command) {
        if (workspaceId == null) {
            throw WorkspaceProjectException.validationFailed("Workspace ID must not be null");
        }
        if (command == null) {
            throw WorkspaceProjectException.validationFailed("Create project command must not be null");
        }

        accessPolicy.requireWorkspaceOwner(principal, workspaceId);

        if (projectRepository.existsByWorkspaceId(workspaceId)) {
            throw WorkspaceProjectException.duplicateResource("A project already exists in this workspace");
        }

        String projectKey = command.projectKey() != null ? command.projectKey().trim() : "";
        if (!KEY_PATTERN.matcher(projectKey).matches()) {
            throw WorkspaceProjectException.validationFailed(
                    "Project key must start with an uppercase letter and be 2-16 uppercase alphanumeric characters"
            );
        }

        String name = command.name() != null ? command.name().trim() : "";
        if (name.isEmpty() || name.length() > 160) {
            throw WorkspaceProjectException.validationFailed("Project name must be between 1 and 160 characters");
        }

        if (command.plannedStart() != null && command.committedDeadline() != null
                && command.committedDeadline().isBefore(command.plannedStart())) {
            throw WorkspaceProjectException.validationFailed("Committed deadline cannot be before planned start date");
        }

        Integer sprintLength = command.sprintLengthDays() != null ? command.sprintLengthDays() : 14;
        if (sprintLength < 1 || sprintLength > 90) {
            throw WorkspaceProjectException.validationFailed("Sprint length days must be between 1 and 90");
        }

        String timezone = command.timezone() != null && !command.timezone().trim().isEmpty()
                ? command.timezone().trim()
                : "Asia/Ho_Chi_Minh";

        setAuditActor(principal.userId());

        try {
            Project project = new Project();
            project.setWorkspaceId(workspaceId);
            project.setProjectKey(projectKey);
            project.setName(name);
            project.setDescription(command.description() != null ? command.description().trim() : null);
            project.setObjective(command.objective() != null ? command.objective().trim() : null);
            project.setStatus(Project.Status.PLANNING);
            project.setTimezone(timezone);
            project.setPlannedStart(command.plannedStart());
            project.setCommittedDeadline(command.committedDeadline());
            project.setSprintLengthDays(sprintLength);
            project = projectRepository.save(project);
            try {
                projectRepository.flush();
            } catch (Exception ex) {
                if (ex instanceof DataIntegrityViolationException) {
                    throw (DataIntegrityViolationException) ex;
                }
            }

            if (Boolean.TRUE.equals(command.selfAssignPm())) {
                ProjectMember pm = new ProjectMember();
                pm.setProjectId(project.getId());
                pm.setWorkspaceId(workspaceId);
                pm.setUserId(principal.userId());
                pm.setStatus(ProjectMember.Status.ACTIVE);
                pm.setJoinedAt(Instant.now());
                pm = projectMemberRepository.save(pm);

                ProjectMemberRole role = new ProjectMemberRole();
                role.setProjectId(project.getId());
                role.setProjectMemberId(pm.getId());
                role.setRole(ProjectMemberRole.Role.PM);
                role.setGrantedBy(principal.userId());
                role.setGrantedAt(Instant.now());
                role.setAcceptedBy(principal.userId());
                role.setAcceptedAt(Instant.now());
                projectMemberRoleRepository.save(role);

                Workspace workspace = workspaceRepository.findById(workspaceId)
                        .orElseThrow(() -> WorkspaceProjectException.resourceNotFound("Workspace not found: " + workspaceId));
                workspace.setStatus(Workspace.Status.ACTIVE);
                workspaceRepository.save(workspace);
            }

            return project;
        } catch (DataIntegrityViolationException ex) {
            throw WorkspaceProjectException.duplicateResource("A project already exists in this workspace or project key constraint violated");
        }
    }

    /**
     * Lists projects in a workspace (0 or 1 project).
     * Caller must hold active workspace membership.
     */
    @Transactional(readOnly = true)
    public List<Project> listProjects(SessionService.Principal principal, UUID workspaceId) {
        if (workspaceId == null) {
            throw WorkspaceProjectException.validationFailed("Workspace ID must not be null");
        }
        accessPolicy.requireWorkspaceMember(principal, workspaceId);

        return projectRepository.findByWorkspaceId(workspaceId)
                .map(List::of)
                .orElseGet(List::of);
    }

    public ProjectResponse createProject(SessionService.Principal principal, UUID workspaceId, CreateProjectRequest request) {
        if (request == null) {
            throw WorkspaceProjectException.validationFailed("Create project request must not be null");
        }
        CreateProjectCommand command = new CreateProjectCommand(
                request.projectKey(),
                request.name(),
                request.description(),
                request.objective(),
                request.timezone(),
                request.plannedStart(),
                null,
                request.sprintLengthDays(),
                request.selfAssignPm()
        );
        Project project = createProject(principal, workspaceId, command);
        return toResponse(project);
    }

    public ProjectResponse updateProject(SessionService.Principal principal, UUID workspaceId, UUID projectId, UpdateProjectRequest request) {
        if (request == null) {
            throw WorkspaceProjectException.validationFailed("Update project request must not be null");
        }
        UpdateProjectCommand command = new UpdateProjectCommand(
                request.name(),
                request.description(),
                request.objective(),
                request.timezone(),
                request.plannedStart(),
                request.committedDeadline(),
                request.sprintLengthDays(),
                request.deadlineApproverId(),
                request.expectedVersion()
        );
        Project project = updateProject(principal, workspaceId, projectId, command);
        return toResponse(project);
    }

    public ProjectResponse activateProject(SessionService.Principal principal, UUID workspaceId, UUID projectId, ActivateProjectRequest request) {
        ActivateProjectCommand command = new ActivateProjectCommand(request != null ? request.expectedVersion() : null);
        Project project = activateProject(principal, workspaceId, projectId, command);
        return toResponse(project);
    }

    public ProjectResponse toResponse(Project project) {
        Long version = project.getVersion();
        try {
            Long currentVersion = jdbcTemplate.queryForObject(
                    "SELECT version FROM projects WHERE id = ?", Long.class, project.getId()
            );
            if (currentVersion != null) {
                version = currentVersion;
            }
        } catch (Exception ignored) {}

        return new ProjectResponse(
                project.getId(),
                project.getWorkspaceId(),
                project.getProjectKey(),
                project.getName(),
                project.getDescription(),
                project.getObjective(),
                project.getStatus(),
                project.getTimezone(),
                project.getPlannedStart(),
                project.getCommittedDeadline(),
                project.getSprintLengthDays(),
                project.getDeadlineApproverId(),
                project.getNextItemNumber(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                version
        );
    }

    /**
     * Retrieves project details with strict tenant scoping.
     * Rejects mismatched workspace/project pairs without information disclosure.
     */
    @Transactional(readOnly = true)
    public Project getProject(SessionService.Principal principal, UUID workspaceId, UUID projectId) {
        if (workspaceId == null || projectId == null) {
            throw WorkspaceProjectException.validationFailed("Workspace ID and Project ID must not be null");
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> WorkspaceProjectException.resourceNotFound("Project not found: " + projectId));

        if (!workspaceId.equals(project.getWorkspaceId())) {
            throw new AccessDeniedException("Access denied");
        }

        accessPolicy.requireWorkspaceMember(principal, workspaceId);

        return project;
    }

    /**
     * Updates project metadata. Caller must hold active PM role in the project.
     * Rejects attempts to alter committedDeadline (requires client deadline confirmation flow).
     * Enforces optimistic locking via expectedVersion.
     */
    public Project updateProject(SessionService.Principal principal, UUID workspaceId, UUID projectId, UpdateProjectCommand command) {
        if (workspaceId == null || projectId == null) {
            throw WorkspaceProjectException.validationFailed("Workspace ID and Project ID must not be null");
        }
        if (command == null) {
            throw WorkspaceProjectException.validationFailed("Update project command must not be null");
        }

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> WorkspaceProjectException.resourceNotFound("Project not found: " + projectId));

        if (!workspaceId.equals(project.getWorkspaceId())) {
            throw new AccessDeniedException("Access denied");
        }

        if (command.committedDeadline() != null && !Objects.equals(command.committedDeadline(), project.getCommittedDeadline())) {
            throw WorkspaceProjectException.clientDeadlineConfirmationRequired(
                    "Committed deadline cannot be altered via generic project update. Client confirmation flow is required."
            );
        }

        if (command.expectedVersion() != null && !command.expectedVersion().equals(project.getVersion())) {
            throw WorkspaceProjectException.planStale(
                    "Project version mismatch. Expected " + command.expectedVersion() + " but was " + project.getVersion()
            );
        }

        if (command.name() != null) {
            String name = command.name().trim();
            if (name.isEmpty() || name.length() > 160) {
                throw WorkspaceProjectException.validationFailed("Project name must be between 1 and 160 characters");
            }
            project.setName(name);
        }

        if (command.description() != null) {
            project.setDescription(command.description().trim());
        }

        if (command.objective() != null) {
            project.setObjective(command.objective().trim());
        }

        if (command.timezone() != null) {
            String tz = command.timezone().trim();
            if (!tz.isEmpty()) {
                project.setTimezone(tz);
            }
        }

        if (command.plannedStart() != null) {
            project.setPlannedStart(command.plannedStart());
        }

        if (command.sprintLengthDays() != null) {
            int length = command.sprintLengthDays();
            if (length < 1 || length > 90) {
                throw WorkspaceProjectException.validationFailed("Sprint length days must be between 1 and 90");
            }
            project.setSprintLengthDays(length);
        }

        if (command.deadlineApproverId() != null) {
            UUID approverId = command.deadlineApproverId();
            Boolean isClient = jdbcTemplate.queryForObject(
                    "SELECT ds_has_project_role(?, ?, 'CLIENT')",
                    Boolean.class,
                    projectId,
                    approverId
            );
            if (!Boolean.TRUE.equals(isClient)) {
                throw WorkspaceProjectException.validationFailed(
                        "Designated deadline approver must be an active CLIENT member of the project"
                );
            }
            project.setDeadlineApproverId(approverId);
        }

        setAuditActor(principal.userId());

        try {
            return projectRepository.save(project);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw WorkspaceProjectException.planStale("Project was concurrently modified");
        }
    }

    /**
     * Activates a project transitioning status from PLANNING/ON_HOLD to ACTIVE.
     * Enforces all preconditions defined in specification and database triggers:
     * - Objective not blank
     * - Planned start date present
     * - At least 1 active non-deleted work item in backlog
     * - Exactly 1 eligible active PM
     * - Deadline approver (if set) holds active CLIENT role
     * - Valid timezone
     */
    public Project activateProject(SessionService.Principal principal, UUID workspaceId, UUID projectId, ActivateProjectCommand command) {
        if (workspaceId == null || projectId == null) {
            throw WorkspaceProjectException.validationFailed("Workspace ID and Project ID must not be null");
        }

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> WorkspaceProjectException.resourceNotFound("Project not found: " + projectId));

        if (!workspaceId.equals(project.getWorkspaceId())) {
            throw new AccessDeniedException("Access denied");
        }

        if (project.getStatus() == Project.Status.ACTIVE) {
            return project;
        }

        if (project.getStatus() != Project.Status.PLANNING && project.getStatus() != Project.Status.ON_HOLD) {
            throw WorkspaceProjectException.activationPreconditionFailed(
                    "Project in status " + project.getStatus() + " cannot be activated"
            );
        }

        if (project.getObjective() == null || project.getObjective().trim().isEmpty()) {
            throw WorkspaceProjectException.activationPreconditionFailed("Project activation requires non-blank objective");
        }

        if (project.getPlannedStart() == null) {
            throw WorkspaceProjectException.activationPreconditionFailed("Project activation requires planned start date");
        }

        long workItemCount = workItemRepository.countByProjectIdAndDeletedAtIsNull(projectId);
        if (workItemCount == 0) {
            throw WorkspaceProjectException.activationPreconditionFailed("Project activation requires at least one work item in backlog");
        }

        Integer pmCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM project_members m WHERE m.project_id = ? AND ds_has_project_role(?, m.user_id, 'PM')",
                Integer.class,
                projectId,
                projectId
        );
        if (pmCount == null || pmCount != 1) {
            throw WorkspaceProjectException.activationPreconditionFailed("Project activation requires exactly one eligible PM");
        }

        if (project.getDeadlineApproverId() != null) {
            Boolean hasClientRole = jdbcTemplate.queryForObject(
                    "SELECT ds_has_project_role(?, ?, 'CLIENT')",
                    Boolean.class,
                    projectId,
                    project.getDeadlineApproverId()
            );
            if (!Boolean.TRUE.equals(hasClientRole)) {
                throw WorkspaceProjectException.activationPreconditionFailed("Deadline representative requires active CLIENT role");
            }
        }

        Integer validTz = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_timezone_names WHERE name = ?",
                Integer.class,
                project.getTimezone()
        );
        if (validTz == null || validTz == 0) {
            throw WorkspaceProjectException.activationPreconditionFailed("Project activation requires a valid timezone");
        }

        if (command != null && command.expectedVersion() != null && !command.expectedVersion().equals(project.getVersion())) {
            throw WorkspaceProjectException.planStale(
                    "Project version mismatch. Expected " + command.expectedVersion() + " but was " + project.getVersion()
            );
        }

        setAuditActor(principal.userId());

        try {
            project.setStatus(Project.Status.ACTIVE);
            return projectRepository.save(project);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw WorkspaceProjectException.planStale("Project was concurrently modified");
        }
    }

    private void setAuditActor(UUID actorId) {
        if (actorId != null) {
            jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, actorId.toString());
        }
    }
}
