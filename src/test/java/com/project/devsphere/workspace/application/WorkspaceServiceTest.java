package com.project.devsphere.workspace.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.workspace.api.dto.CreateWorkspaceRequest;
import com.project.devsphere.workspace.api.dto.UpdateWorkspaceRequest;
import com.project.devsphere.workspace.api.dto.WorkspacePageResponse;
import com.project.devsphere.workspace.api.dto.WorkspaceResponse;
import com.project.devsphere.workspace.api.dto.WorkspaceSetupResponse;
import com.project.devsphere.workspace.domain.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
class WorkspaceServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private AccessPolicy accessPolicy;

    private WorkspaceService workspaceService;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private SessionService.Principal principal;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(
                jdbcTemplate,
                transactionTemplate,
                accessPolicy
        );
        principal = new SessionService.Principal(userId, sessionId, "Test User", "USER");
    }

    @Test
    void createWorkspace_success() {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest("Engineering", "eng-team", "Eng description");

        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM workspaces WHERE slug"), eq(Integer.class), eq("eng-team")))
                .thenReturn(0);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });

        when(jdbcTemplate.query(contains("FROM workspaces WHERE id = ?"), any(RowMapper.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(workspaceId);
                    when(rs.getString("slug")).thenReturn("eng-team");
                    when(rs.getString("name")).thenReturn("Engineering");
                    when(rs.getString("description")).thenReturn("Eng description");
                    when(rs.getString("status")).thenReturn("SETUP_INCOMPLETE");
                    when(rs.getObject("owner_id")).thenReturn(userId);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
                    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.now()));
                    when(rs.getLong("version")).thenReturn(0L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        WorkspaceResponse response = workspaceService.createWorkspace(principal, request);

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("Engineering");
        assertThat(response.slug()).isEqualTo("eng-team");
        assertThat(response.ownerId()).isEqualTo(userId);
        assertThat(response.status()).isEqualTo(Workspace.Status.SETUP_INCOMPLETE);

        verify(jdbcTemplate).queryForObject(contains("set_config"), eq(String.class), eq(userId.toString()));
    }

    @Test
    void createWorkspace_duplicateSlug_throwsConflict() {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest("Engineering", "eng-team", null);

        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM workspaces WHERE slug"), eq(Integer.class), eq("eng-team")))
                .thenReturn(1);

        assertThatThrownBy(() -> workspaceService.createWorkspace(principal, request))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("DUPLICATE_RESOURCE");
                });
    }

    @Test
    void createWorkspace_concurrentDuplicate_throwsConflict() {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest("Engineering", "eng-team", null);

        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM workspaces WHERE slug"), eq(Integer.class), eq("eng-team")))
                .thenReturn(0);
        when(transactionTemplate.execute(any())).thenThrow(new DuplicateKeyException("Unique slug"));

        assertThatThrownBy(() -> workspaceService.createWorkspace(principal, request))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("DUPLICATE_RESOURCE");
                });
    }

    @Test
    void listWorkspaces_scopesToUser() {
        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM workspaces w"), eq(Long.class), eq(userId)))
                .thenReturn(1L);

        when(jdbcTemplate.query(contains("FROM workspaces w"), any(RowMapper.class), eq(userId), eq(10), eq(0)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(workspaceId);
                    when(rs.getString("slug")).thenReturn("my-ws");
                    when(rs.getString("name")).thenReturn("My Workspace");
                    when(rs.getString("description")).thenReturn("Desc");
                    when(rs.getString("status")).thenReturn("ACTIVE");
                    when(rs.getObject("owner_id")).thenReturn(userId);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
                    when(rs.getTimestamp("updated_at")).thenReturn(null);
                    when(rs.getLong("version")).thenReturn(0L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        WorkspacePageResponse<WorkspaceResponse> result = workspaceService.listWorkspaces(principal, 0, 10);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).name()).isEqualTo("My Workspace");
    }

    @Test
    void getWorkspace_success() {
        when(jdbcTemplate.query(contains("FROM workspaces WHERE id = ?"), any(RowMapper.class), eq(workspaceId)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(workspaceId);
                    when(rs.getString("slug")).thenReturn("ws-slug");
                    when(rs.getString("name")).thenReturn("Workspace One");
                    when(rs.getString("description")).thenReturn("Desc");
                    when(rs.getString("status")).thenReturn("ACTIVE");
                    when(rs.getObject("owner_id")).thenReturn(userId);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
                    when(rs.getTimestamp("updated_at")).thenReturn(null);
                    when(rs.getLong("version")).thenReturn(1L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        WorkspaceResponse result = workspaceService.getWorkspace(principal, workspaceId);

        assertThat(result.id()).isEqualTo(workspaceId);
        verify(accessPolicy).requireWorkspaceMember(principal, workspaceId);
    }

    @Test
    void getWorkspace_notFound_throws404() {
        when(jdbcTemplate.query(contains("FROM workspaces WHERE id = ?"), any(RowMapper.class), eq(workspaceId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> workspaceService.getWorkspace(principal, workspaceId))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(wpe.getCode()).isEqualTo("RESOURCE_NOT_FOUND");
                });
    }

    @Test
    void updateWorkspace_optimisticLockMismatch_throwsPlanStale() {
        UpdateWorkspaceRequest request = new UpdateWorkspaceRequest("New Name", "New Desc", 1L);

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            when(jdbcTemplate.queryForList(contains("FROM workspaces WHERE id = ? FOR UPDATE"), eq(workspaceId)))
                    .thenReturn(List.of(Map.of("id", workspaceId, "version", 2L)));
            return callback.doInTransaction(new SimpleTransactionStatus());
        });

        assertThatThrownBy(() -> workspaceService.updateWorkspace(principal, workspaceId, request))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("PLAN_STALE");
                });
    }

    @Test
    void getWorkspaceSetup_noProject() {
        when(jdbcTemplate.query(contains("FROM workspaces WHERE id = ?"), any(RowMapper.class), eq(workspaceId)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(workspaceId);
                    when(rs.getString("slug")).thenReturn("test-ws");
                    when(rs.getString("name")).thenReturn("Test");
                    when(rs.getString("description")).thenReturn(null);
                    when(rs.getString("status")).thenReturn("SETUP_INCOMPLETE");
                    when(rs.getObject("owner_id")).thenReturn(userId);
                    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
                    when(rs.getTimestamp("updated_at")).thenReturn(null);
                    when(rs.getLong("version")).thenReturn(0L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM projects WHERE workspace_id"), eq(Integer.class), eq(workspaceId)))
                .thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM project_members pm"), eq(Integer.class), eq(workspaceId)))
                .thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM workspace_invitations"), eq(Long.class), eq(workspaceId)))
                .thenReturn(0L);

        WorkspaceSetupResponse setup = workspaceService.getWorkspaceSetup(principal, workspaceId);

        assertThat(setup.projectConfigured()).isFalse();
        assertThat(setup.pmAssigned()).isFalse();
        assertThat(setup.status()).isEqualTo(Workspace.Status.SETUP_INCOMPLETE);
        verify(accessPolicy).requireWorkspaceOwner(principal, workspaceId);
    }
}
