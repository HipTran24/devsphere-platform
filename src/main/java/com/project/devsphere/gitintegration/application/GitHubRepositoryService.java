package com.project.devsphere.gitintegration.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.gitintegration.domain.GitHubRepository;
import com.project.devsphere.gitintegration.infrastructure.persistence.GitHubRepositoryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class GitHubRepositoryService {

    private final GitHubRepositoryRepository repositoryRepository;
    private final AccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;

    public GitHubRepositoryService(
            GitHubRepositoryRepository repositoryRepository,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate) {
        this.repositoryRepository = Objects.requireNonNull(repositoryRepository, "repositoryRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /**
     * Connects a GitHub repository to a project.
     * Guarded by PM role.
     * Enforces single active repo per project and global repo uniqueness.
     */
    public GitHubRepository connectRepository(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            ConnectRepositoryCommand command) {
        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (command == null) {
            throw GitHubException.validationFailed("Connect repository command must not be null");
        }
        if (command.githubInstallationId() == null || command.githubInstallationId() <= 0) {
            throw GitHubException.validationFailed("githubInstallationId must be a positive integer");
        }
        if (command.githubRepoId() == null || command.githubRepoId() <= 0) {
            throw GitHubException.validationFailed("githubRepoId must be a positive integer");
        }
        if (command.fullName() == null || command.fullName().isBlank()) {
            throw GitHubException.validationFailed("fullName must not be blank");
        }

        // Invariant 1: Check if project already has an active connected repo
        if (repositoryRepository.existsByProjectIdAndActiveTrue(projectId)) {
            throw GitHubException.repositoryAlreadyConnected("Project already has an active connected GitHub repository");
        }

        // Invariant 2: Check if githubRepoId is already connected to another project
        if (repositoryRepository.existsByGithubRepoId(command.githubRepoId())) {
            throw GitHubException.duplicateRepository("GitHub repository is already connected to a project");
        }

        GitHubRepository repository = new GitHubRepository();
        repository.setWorkspaceId(workspaceId);
        repository.setProjectId(projectId);
        repository.setGithubInstallationId(command.githubInstallationId());
        repository.setGithubRepoId(command.githubRepoId());
        repository.setFullName(command.fullName().trim());
        repository.setDefaultBranch(command.defaultBranch());
        repository.setPrivateRepository(command.privateRepository());
        repository.setActive(true);

        try {
            repository = repositoryRepository.save(repository);
            repositoryRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("github_repo_id")) {
                throw GitHubException.duplicateRepository("GitHub repository is already connected to a project");
            }
            throw GitHubException.duplicateResource("Repository connection conflict: " + ex.getMessage());
        }

        return repository;
    }

    /**
     * Lists repositories for a project.
     * Guarded by project membership.
     */
    @Transactional(readOnly = true)
    public List<GitHubRepository> listRepositories(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        return repositoryRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * Gets the active connected repository for a project.
     * Guarded by project membership.
     */
    @Transactional(readOnly = true)
    public Optional<GitHubRepository> getConnectedRepository(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        return repositoryRepository.findByProjectIdAndActiveTrue(projectId);
    }

    /**
     * Disconnects a repository by marking active = false.
     * Guarded by PM role.
     */
    public GitHubRepository disconnectRepository(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID repositoryId) {
        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        GitHubRepository repository = repositoryRepository.findByIdAndProjectId(repositoryId, projectId)
                .orElseThrow(() -> GitHubException.repositoryNotFound("Repository not found with ID: " + repositoryId));

        repository.setActive(false);
        repository = repositoryRepository.save(repository);
        repositoryRepository.flush();

        return repository;
    }

    private void setActorId(SessionService.Principal principal) {
        if (principal != null && principal.userId() != null) {
            try {
                jdbcTemplate.execute("SELECT set_config('devsphere.actor_id', '" + principal.userId() + "', true)");
            } catch (Exception ignored) {
            }
        }
    }
}
