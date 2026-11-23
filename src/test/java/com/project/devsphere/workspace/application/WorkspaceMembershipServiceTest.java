package com.project.devsphere.workspace.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
class WorkspaceMembershipServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AccessPolicy accessPolicy;

    private WorkspaceMembershipService membershipService;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private SessionService.Principal principal;

    @BeforeEach
    void setUp() {
        membershipService = new WorkspaceMembershipService(jdbcTemplate, accessPolicy);
        principal = new SessionService.Principal(userId, sessionId, "Test User", "USER");
    }

    @Test
    @DisplayName("createInvitation fails if email is blank")
    void createInvitationBlankEmail() {
        assertThatThrownBy(() -> membershipService.createInvitation(principal, workspaceId, new CreateInvitationCommand("   ")))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> assertThat(((WorkspaceProjectException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("createInvitation fails if user is already an active member")
    void createInvitationExistingMember() {
        when(jdbcTemplate.queryForObject(contains("workspace_members wm"), eq(Integer.class), eq(workspaceId), anyString()))
                .thenReturn(1);

        assertThatThrownBy(() -> membershipService.createInvitation(principal, workspaceId, new CreateInvitationCommand("member@example.com")))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("DUPLICATE_RESOURCE");
                });
    }

    @Test
    @DisplayName("createInvitation fails if pending invitation already exists")
    void createInvitationDuplicatePending() {
        when(jdbcTemplate.queryForObject(contains("workspace_members wm"), eq(Integer.class), eq(workspaceId), anyString()))
                .thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("workspace_invitations"), eq(Integer.class), eq(workspaceId), anyString()))
                .thenReturn(1);

        assertThatThrownBy(() -> membershipService.createInvitation(principal, workspaceId, new CreateInvitationCommand("pending@example.com")))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("DUPLICATE_RESOURCE");
                });
    }

    @Test
    @DisplayName("createInvitation succeeds and returns unhashed token")
    void createInvitationSuccess() {
        when(jdbcTemplate.queryForObject(contains("workspace_members wm"), eq(Integer.class), eq(workspaceId), anyString()))
                .thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("workspace_invitations"), eq(Integer.class), eq(workspaceId), anyString()))
                .thenReturn(0);

        InvitationCreationResult result = membershipService.createInvitation(
                principal,
                workspaceId,
                new CreateInvitationCommand("new@example.com")
        );

        assertThat(result).isNotNull();
        assertThat(result.email()).isEqualTo("new@example.com");
        assertThat(result.token()).isNotBlank();
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.workspaceId()).isEqualTo(workspaceId);

        verify(accessPolicy).requireWorkspaceOwner(principal, workspaceId);
    }

    @Test
    @DisplayName("acceptInvitation fails if invitation not found")
    void acceptInvitationNotFound() {
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> membershipService.acceptInvitation(principal, new AcceptInvitationCommand("invalid-token")))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> assertThat(((WorkspaceProjectException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("acceptInvitation fails if caller email does not match invitation email")
    void acceptInvitationEmailMismatch() {
        Map<String, Object> inv = Map.of(
                "id", UUID.randomUUID(),
                "workspace_id", workspaceId,
                "email", "target@example.com",
                "status", "PENDING",
                "expires_at", Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS))
        );
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of(inv));
        when(jdbcTemplate.queryForObject(contains("SELECT email FROM users"), eq(String.class), eq(userId)))
                .thenReturn("different@example.com");

        assertThatThrownBy(() -> membershipService.acceptInvitation(principal, new AcceptInvitationCommand("some-token")))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(wpe.getCode()).isEqualTo("ACCESS_DENIED");
                });
    }

    @Test
    @DisplayName("acceptInvitation fails if expired")
    void acceptInvitationExpired() {
        Map<String, Object> inv = Map.of(
                "id", UUID.randomUUID(),
                "workspace_id", workspaceId,
                "email", "target@example.com",
                "status", "PENDING",
                "expires_at", Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS))
        );
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of(inv));

        assertThatThrownBy(() -> membershipService.acceptInvitation(principal, new AcceptInvitationCommand("some-token")))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.GONE);
                    assertThat(wpe.getCode()).isEqualTo("INVITATION_EXPIRED");
                });
    }

    @Test
    @DisplayName("removeMember rejects removing workspace owner with LAST_OWNER")
    void removeMemberRejectsOwner() {
        // Attempting to remove self / owner
        assertThatThrownBy(() -> membershipService.removeMember(principal, workspaceId, userId))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("LAST_OWNER");
                });
    }

    @Test
    @DisplayName("removeMember translates trigger exception to ACTIVE_PROJECT_REQUIRES_PM")
    void removeMemberActivePmTriggerException() {
        UUID targetUser = UUID.randomUUID();
        when(jdbcTemplate.queryForList(anyString(), eq(workspaceId), eq(targetUser)))
                .thenReturn(List.of(Map.of("role", "MEMBER", "status", "ACTIVE")));

        when(jdbcTemplate.update(contains("UPDATE workspace_members SET status = 'REMOVED'"), eq(workspaceId), eq(targetUser)))
                .thenThrow(new DataIntegrityViolationException("ERROR: Transfer PM before removing Workspace membership"));

        assertThatThrownBy(() -> membershipService.removeMember(principal, workspaceId, targetUser))
                .isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("ACTIVE_PROJECT_REQUIRES_PM");
                });
    }

    @Test
    @DisplayName("transferOwnership fails on stale version with PLAN_STALE")
    void transferOwnershipStaleVersion() {
        UUID targetUser = UUID.randomUUID();
        when(jdbcTemplate.query(contains("SELECT role FROM workspace_members"), any(RowMapper.class), eq(workspaceId), eq(targetUser)))
                .thenReturn(List.of("MEMBER"));
        when(jdbcTemplate.query(contains("SELECT version FROM workspaces"), any(RowMapper.class), eq(workspaceId)))
                .thenReturn(List.of(5L));

        assertThatThrownBy(() -> membershipService.transferOwnership(
                principal,
                workspaceId,
                new TransferOwnershipCommand(targetUser, 3L, "transfer")
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(wpe.getCode()).isEqualTo("PLAN_STALE");
                });
    }

    @Test
    @DisplayName("transferOwnership succeeds and swaps roles")
    void transferOwnershipSuccess() {
        UUID targetUser = UUID.randomUUID();
        when(jdbcTemplate.query(contains("SELECT role FROM workspace_members"), any(RowMapper.class), eq(workspaceId), eq(targetUser)))
                .thenReturn(List.of("MEMBER"));
        when(jdbcTemplate.query(contains("SELECT version FROM workspaces"), any(RowMapper.class), eq(workspaceId)))
                .thenReturn(List.of(5L));

        membershipService.transferOwnership(
                principal,
                workspaceId,
                new TransferOwnershipCommand(targetUser, 5L, "transfer")
        );

        verify(accessPolicy).requireWorkspaceOwner(principal, workspaceId);
        verify(jdbcTemplate).update(contains("UPDATE workspace_members SET role = 'MEMBER'"), eq(workspaceId), eq(userId));
        verify(jdbcTemplate).update(contains("UPDATE workspace_members SET role = 'OWNER'"), eq(workspaceId), eq(targetUser));
        verify(jdbcTemplate).update(contains("UPDATE workspaces SET owner_id = ?"), eq(targetUser), eq(workspaceId));
    }
}
