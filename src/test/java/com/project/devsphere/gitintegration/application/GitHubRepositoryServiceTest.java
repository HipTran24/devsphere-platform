package com.project.devsphere.gitintegration.application;

import com.project.devsphere.gitintegration.domain.GitHubRepository;
import com.project.devsphere.gitintegration.infrastructure.persistence.GitHubRepositoryRepository;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitHubRepositoryServiceTest {

    @Mock private GitHubRepositoryRepository repositoryRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private JdbcTemplate jdbcTemplate;

    private GitHubRepositoryService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private SessionService.Principal principal;

    @BeforeEach
    void setUp() {
        service = new GitHubRepositoryService(repositoryRepository, accessPolicy, jdbcTemplate);
        principal = new SessionService.Principal(userId, sessionId, "Developer", "USER");
    }

    @Test
    @DisplayName("connectRepository succeeds for PM with valid non-duplicate parameters")
    void connectRepository_success() {
        ConnectRepositoryCommand command = new ConnectRepositoryCommand(
                12345L,
                67890L,
                "devsphere-org/backend-core",
                "main",
                true
        );

        when(repositoryRepository.existsByProjectIdAndActiveTrue(projectId)).thenReturn(false);
        when(repositoryRepository.existsByGithubRepoId(67890L)).thenReturn(false);
        when(repositoryRepository.save(any(GitHubRepository.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitHubRepository result = service.connectRepository(principal, workspaceId, projectId, command);

        verify(accessPolicy).requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);
        assertThat(result).isNotNull();
        assertThat(result.getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(result.getProjectId()).isEqualTo(projectId);
        assertThat(result.getGithubInstallationId()).isEqualTo(12345L);
        assertThat(result.getGithubRepoId()).isEqualTo(67890L);
        assertThat(result.getFullName()).isEqualTo("devsphere-org/backend-core");
        assertThat(result.getDefaultBranch()).isEqualTo("main");
        assertThat(result.getPrivateRepository()).isTrue();
        assertThat(result.getActive()).isTrue();

        ArgumentCaptor<GitHubRepository> captor = ArgumentCaptor.forClass(GitHubRepository.class);
        verify(repositoryRepository).save(captor.capture());
        assertThat(captor.getValue().getActive()).isTrue();
    }

    @Test
    @DisplayName("connectRepository fails when project already has an active connected repo")
    void connectRepository_projectAlreadyConnected_throwsConflict() {
        ConnectRepositoryCommand command = new ConnectRepositoryCommand(
                12345L, 67890L, "org/repo", "main", true
        );

        when(repositoryRepository.existsByProjectIdAndActiveTrue(projectId)).thenReturn(true);

        assertThatThrownBy(() -> service.connectRepository(principal, workspaceId, projectId, command))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("Project already has an active connected GitHub repository")
                .satisfies(ex -> {
                    GitHubException ghe = (GitHubException) ex;
                    assertThat(ghe.getCode()).isEqualTo("REPOSITORY_ALREADY_CONNECTED");
                });

        verify(repositoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("connectRepository fails when githubRepoId is already connected globally")
    void connectRepository_duplicateRepoId_throwsConflict() {
        ConnectRepositoryCommand command = new ConnectRepositoryCommand(
                12345L, 67890L, "org/repo", "main", true
        );

        when(repositoryRepository.existsByProjectIdAndActiveTrue(projectId)).thenReturn(false);
        when(repositoryRepository.existsByGithubRepoId(67890L)).thenReturn(true);

        assertThatThrownBy(() -> service.connectRepository(principal, workspaceId, projectId, command))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("GitHub repository is already connected to a project")
                .satisfies(ex -> {
                    GitHubException ghe = (GitHubException) ex;
                    assertThat(ghe.getCode()).isEqualTo("DUPLICATE_REPOSITORY");
                });

        verify(repositoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("connectRepository fails when required fields are missing or invalid")
    void connectRepository_invalidArguments_throwsValidationFailed() {
        ConnectRepositoryCommand invalidInstallId = new ConnectRepositoryCommand(
                -1L, 67890L, "org/repo", "main", true
        );
        assertThatThrownBy(() -> service.connectRepository(principal, workspaceId, projectId, invalidInstallId))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("githubInstallationId");

        ConnectRepositoryCommand invalidRepoId = new ConnectRepositoryCommand(
                12345L, 0L, "org/repo", "main", true
        );
        assertThatThrownBy(() -> service.connectRepository(principal, workspaceId, projectId, invalidRepoId))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("githubRepoId");

        ConnectRepositoryCommand blankName = new ConnectRepositoryCommand(
                12345L, 67890L, "   ", "main", true
        );
        assertThatThrownBy(() -> service.connectRepository(principal, workspaceId, projectId, blankName))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("fullName");
    }

    @Test
    @DisplayName("connectRepository is rejected when caller is not PM")
    void connectRepository_accessDeniedForNonPm() {
        ConnectRepositoryCommand command = new ConnectRepositoryCommand(
                12345L, 67890L, "org/repo", "main", true
        );

        when(accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM))
                .thenThrow(new AccessDeniedException("Access denied: active PM role required"));

        assertThatThrownBy(() -> service.connectRepository(principal, workspaceId, projectId, command))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("active PM role required");

        verify(repositoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("listRepositories returns project repositories for active member")
    void listRepositories_scopedToMembers() {
        GitHubRepository r1 = new GitHubRepository();
        r1.setId(UUID.randomUUID());
        r1.setFullName("org/repo-1");

        GitHubRepository r2 = new GitHubRepository();
        r2.setId(UUID.randomUUID());
        r2.setFullName("org/repo-2");

        when(repositoryRepository.findByProjectIdOrderByCreatedAtDesc(projectId)).thenReturn(List.of(r1, r2));

        List<GitHubRepository> list = service.listRepositories(principal, workspaceId, projectId);

        verify(accessPolicy).requireProjectMember(principal, workspaceId, projectId);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getFullName()).isEqualTo("org/repo-1");
    }

    @Test
    @DisplayName("disconnectRepository sets active=false")
    void disconnectRepository_success() {
        GitHubRepository repo = new GitHubRepository();
        repo.setId(repositoryId);
        repo.setProjectId(projectId);
        repo.setActive(true);

        when(repositoryRepository.findByIdAndProjectId(repositoryId, projectId)).thenReturn(Optional.of(repo));
        when(repositoryRepository.save(any(GitHubRepository.class))).thenAnswer(inv -> inv.getArgument(0));

        GitHubRepository updated = service.disconnectRepository(principal, workspaceId, projectId, repositoryId);

        verify(accessPolicy).requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);
        assertThat(updated.getActive()).isFalse();
        verify(repositoryRepository).save(repo);
    }

    @Test
    @DisplayName("disconnectRepository throws 404 when repository does not exist")
    void disconnectRepository_notFound() {
        when(repositoryRepository.findByIdAndProjectId(repositoryId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.disconnectRepository(principal, workspaceId, projectId, repositoryId))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("Repository not found")
                .satisfies(ex -> {
                    GitHubException ghe = (GitHubException) ex;
                    assertThat(ghe.getCode()).isEqualTo("REPOSITORY_NOT_FOUND");
                });
    }
}
