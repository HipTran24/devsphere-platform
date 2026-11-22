package com.project.devsphere.workspace.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class WorkspaceMembershipService {

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;
    private final SecureRandom secureRandom = new SecureRandom();

    public WorkspaceMembershipService(JdbcTemplate jdbcTemplate, AccessPolicy accessPolicy) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    /**
     * Lists active and past members of a workspace.
     * Guarded by AccessPolicy.requireWorkspaceMember.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceMemberDetails> listMembers(SessionService.Principal principal, UUID workspaceId) {
        accessPolicy.requireWorkspaceMember(principal, workspaceId);

        String sql = """
                SELECT wm.user_id, u.email, u.display_name, wm.role, wm.status, wm.joined_at
                FROM workspace_members wm
                JOIN users u ON u.id = wm.user_id
                WHERE wm.workspace_id = ?
                ORDER BY wm.joined_at ASC
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new WorkspaceMemberDetails(
                UUID.fromString(rs.getString("user_id")),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("joined_at"))
        ), workspaceId);
    }

    /**
     * Issues a single-use invitation to a specified email address.
     * Guarded by AccessPolicy.requireWorkspaceOwner.
     */
    @Transactional
    public InvitationCreationResult createInvitation(
            SessionService.Principal principal,
            UUID workspaceId,
            CreateInvitationCommand command) {
        accessPolicy.requireWorkspaceOwner(principal, workspaceId);

        if (command == null || command.email() == null || command.email().isBlank()) {
            throw WorkspaceProjectException.validationFailed("Email must not be blank");
        }

        String normalizedEmail = command.email().trim().toLowerCase(Locale.ROOT);

        // 1. Check if user is already an active workspace member
        String existingMemberSql = """
                SELECT count(*)
                FROM workspace_members wm
                JOIN users u ON u.id = wm.user_id
                WHERE wm.workspace_id = ?
                  AND lower(u.email) = ?
                  AND wm.status = 'ACTIVE'
                """;
        Integer existingCount = jdbcTemplate.queryForObject(existingMemberSql, Integer.class, workspaceId, normalizedEmail);
        if (existingCount != null && existingCount > 0) {
            throw WorkspaceProjectException.duplicate("User is already an active member of this workspace");
        }

        // 2. Check if a pending unexpired invitation exists for this email
        String pendingInvSql = """
                SELECT count(*)
                FROM workspace_invitations
                WHERE workspace_id = ?
                  AND lower(email) = ?
                  AND status = 'PENDING'
                  AND expires_at > now()
                """;
        Integer pendingCount = jdbcTemplate.queryForObject(pendingInvSql, Integer.class, workspaceId, normalizedEmail);
        if (pendingCount != null && pendingCount > 0) {
            throw WorkspaceProjectException.duplicate("Pending invitation already exists for this email");
        }

        // 3. Generate 256-bit cryptographically secure raw token
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String rawToken = HexFormat.of().formatHex(tokenBytes);
        String tokenHash = sha256Hex(rawToken);

        UUID invitationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        String insertSql = """
                INSERT INTO workspace_invitations (
                    id, workspace_id, email, token_hash, invited_by, role, status, expires_at, created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, ?, ?, 'MEMBER', 'PENDING', ?, now(), now(), 0
                )
                """;

        jdbcTemplate.update(
                insertSql,
                invitationId,
                workspaceId,
                normalizedEmail,
                tokenHash,
                principal.userId(),
                Timestamp.from(expiresAt)
        );

        return new InvitationCreationResult(
                invitationId,
                workspaceId,
                normalizedEmail,
                "MEMBER",
                "PENDING",
                expiresAt,
                rawToken
        );
    }

    /**
     * Lists invitations for a workspace without exposing token or token_hash.
     * Guarded by AccessPolicy.requireWorkspaceOwner.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceInvitationSummary> listInvitations(SessionService.Principal principal, UUID workspaceId) {
        accessPolicy.requireWorkspaceOwner(principal, workspaceId);

        String sql = """
                SELECT id, workspace_id, email, invited_by, role, status, expires_at, accepted_at, created_at
                FROM workspace_invitations
                WHERE workspace_id = ?
                ORDER BY created_at DESC
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new WorkspaceInvitationSummary(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("workspace_id")),
                rs.getString("email"),
                UUID.fromString(rs.getString("invited_by")),
                rs.getString("role"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("expires_at")),
                toInstant(rs.getTimestamp("accepted_at")),
                toInstant(rs.getTimestamp("created_at"))
        ), workspaceId);
    }

    /**
     * Revokes an outstanding invitation.
     * Guarded by AccessPolicy.requireWorkspaceOwner.
     */
    @Transactional
    public void revokeInvitation(SessionService.Principal principal, UUID workspaceId, UUID invitationId) {
        accessPolicy.requireWorkspaceOwner(principal, workspaceId);

        String findSql = "SELECT status FROM workspace_invitations WHERE id = ? AND workspace_id = ?";
        List<String> statuses = jdbcTemplate.query(findSql, (rs, rowNum) -> rs.getString("status"), invitationId, workspaceId);
        if (statuses.isEmpty()) {
            throw WorkspaceProjectException.notFound("Invitation not found");
        }

        jdbcTemplate.update(
                "UPDATE workspace_invitations SET status = 'REVOKED', updated_at = now() WHERE id = ? AND workspace_id = ?",
                invitationId,
                workspaceId
        );
    }

    /**
     * Accepts a workspace invitation.
     * Enforces caller email verification, expiry check, single-use consumption, and creates active membership.
     */
    @Transactional
    public void acceptInvitation(SessionService.Principal principal, AcceptInvitationCommand command) {
        accessPolicy.requireAuthenticatedUser(principal);

        if (command == null || command.token() == null || command.token().isBlank()) {
            throw WorkspaceProjectException.validationFailed("Token must not be blank");
        }

        String rawToken = command.token().trim();
        String tokenHash = sha256Hex(rawToken);

        String findSql = """
                SELECT id, workspace_id, email::text AS email, status, expires_at
                FROM workspace_invitations
                WHERE token_hash = ?
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(findSql, tokenHash);
        if (rows.isEmpty()) {
            throw WorkspaceProjectException.notFound("Invitation not found");
        }

        Map<String, Object> inv = rows.get(0);
        UUID invitationId = (UUID) inv.get("id");
        UUID workspaceId = (UUID) inv.get("workspace_id");
        String inviteEmail = Objects.toString(inv.get("email"), null);
        String status = Objects.toString(inv.get("status"), null);
        Instant expiresAt = toInstant((Timestamp) inv.get("expires_at"));

        if ("REVOKED".equalsIgnoreCase(status)) {
            throw WorkspaceProjectException.validationFailed("Invitation has been revoked");
        }
        if ("ACCEPTED".equalsIgnoreCase(status)) {
            throw WorkspaceProjectException.duplicate("Invitation has already been accepted");
        }
        if ("DECLINED".equalsIgnoreCase(status)) {
            throw WorkspaceProjectException.validationFailed("Invitation has been declined");
        }
        if ("EXPIRED".equalsIgnoreCase(status) || (expiresAt != null && expiresAt.isBefore(Instant.now()))) {
            jdbcTemplate.update("UPDATE workspace_invitations SET status = 'EXPIRED', updated_at = now() WHERE id = ?", invitationId);
            throw WorkspaceProjectException.invitationExpired("Invitation has expired");
        }

        // Verify caller's verified email matches invitation email
        String callerEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?",
                String.class,
                principal.userId()
        );

        if (callerEmail == null || !callerEmail.equalsIgnoreCase(inviteEmail)) {
            throw WorkspaceProjectException.accessDenied("Caller email does not match invitation email");
        }

        // Atomically mark invitation accepted and upsert workspace membership
        jdbcTemplate.update(
                "UPDATE workspace_invitations SET status = 'ACCEPTED', accepted_at = now(), updated_at = now() WHERE id = ?",
                invitationId
        );

        String upsertMemberSql = """
                INSERT INTO workspace_members (id, workspace_id, user_id, role, status, joined_at, created_at, updated_at, version)
                VALUES (gen_random_uuid(), ?, ?, 'MEMBER', 'ACTIVE', now(), now(), now(), 0)
                ON CONFLICT (workspace_id, user_id) DO UPDATE
                SET status = 'ACTIVE', role = 'MEMBER', updated_at = now()
                """;

        jdbcTemplate.update(upsertMemberSql, workspaceId, principal.userId());
    }

    /**
     * Declines a workspace invitation.
     */
    @Transactional
    public void declineInvitation(SessionService.Principal principal, DeclineInvitationCommand command) {
        accessPolicy.requireAuthenticatedUser(principal);

        if (command == null || command.token() == null || command.token().isBlank()) {
            throw WorkspaceProjectException.validationFailed("Token must not be blank");
        }

        String rawToken = command.token().trim();
        String tokenHash = sha256Hex(rawToken);

        String findSql = "SELECT id, email::text AS email, status, expires_at FROM workspace_invitations WHERE token_hash = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(findSql, tokenHash);
        if (rows.isEmpty()) {
            throw WorkspaceProjectException.notFound("Invitation not found");
        }

        Map<String, Object> inv = rows.get(0);
        UUID invitationId = (UUID) inv.get("id");
        String inviteEmail = Objects.toString(inv.get("email"), null);
        String status = Objects.toString(inv.get("status"), null);
        Instant expiresAt = toInstant((Timestamp) inv.get("expires_at"));

        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            throw WorkspaceProjectException.invitationExpired("Invitation has expired");
        }

        String callerEmail = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, principal.userId());
        if (callerEmail == null || !callerEmail.equalsIgnoreCase(inviteEmail)) {
            throw WorkspaceProjectException.accessDenied("Caller email does not match invitation email");
        }

        if ("PENDING".equalsIgnoreCase(status)) {
            jdbcTemplate.update("UPDATE workspace_invitations SET status = 'DECLINED', updated_at = now() WHERE id = ?", invitationId);
        }
    }

    /**
     * Removes a member from a workspace.
     * Enforces LAST_OWNER invariant and translates trigger exception to ACTIVE_PROJECT_REQUIRES_PM.
     */
    @Transactional
    public void removeMember(SessionService.Principal principal, UUID workspaceId, UUID targetUserId) {
        accessPolicy.requireWorkspaceOwner(principal, workspaceId);

        if (principal.userId().equals(targetUserId)) {
            throw WorkspaceProjectException.lastOwner("Cannot remove workspace owner");
        }

        String memberSql = "SELECT role, status FROM workspace_members WHERE workspace_id = ? AND user_id = ?";
        List<Map<String, Object>> members = jdbcTemplate.queryForList(memberSql, workspaceId, targetUserId);
        if (members.isEmpty()) {
            throw WorkspaceProjectException.notFound("Workspace member not found");
        }

        String role = (String) members.get(0).get("role");
        if ("OWNER".equalsIgnoreCase(role)) {
            throw WorkspaceProjectException.lastOwner("Cannot remove workspace owner");
        }

        try {
            jdbcTemplate.update(
                    "UPDATE workspace_members SET status = 'REMOVED', updated_at = now() WHERE workspace_id = ? AND user_id = ?",
                    workspaceId,
                    targetUserId
            );
        } catch (DataAccessException e) {
            String rootMessage = getRootMessage(e);
            if (rootMessage != null && rootMessage.contains("Transfer PM before removing Workspace membership")) {
                throw WorkspaceProjectException.activeProjectRequiresPm("Transfer PM before removing Workspace membership");
            }
            throw e;
        }
    }

    /**
     * Transfers ownership of a workspace to another active member.
     * Preserves existing PM assignments (TC-30).
     */
    @Transactional
    public void transferOwnership(SessionService.Principal principal, UUID workspaceId, TransferOwnershipCommand command) {
        accessPolicy.requireWorkspaceOwner(principal, workspaceId);

        if (command == null || command.targetUserId() == null) {
            throw WorkspaceProjectException.validationFailed("Target user id must be provided");
        }

        if (principal.userId().equals(command.targetUserId())) {
            throw WorkspaceProjectException.validationFailed("Cannot transfer ownership to yourself");
        }

        // Verify target user is an active member
        String targetMemberSql = "SELECT role FROM workspace_members WHERE workspace_id = ? AND user_id = ? AND status = 'ACTIVE'";
        List<String> targetRoles = jdbcTemplate.query(targetMemberSql, (rs, rowNum) -> rs.getString("role"), workspaceId, command.targetUserId());
        if (targetRoles.isEmpty()) {
            throw WorkspaceProjectException.validationFailed("Target user must be an active workspace member");
        }

        // Check workspace optimistic locking version
        String versionSql = "SELECT version FROM workspaces WHERE id = ? FOR UPDATE";
        List<Long> versions = jdbcTemplate.query(versionSql, (rs, rowNum) -> rs.getLong("version"), workspaceId);
        if (versions.isEmpty()) {
            throw WorkspaceProjectException.notFound("Workspace not found");
        }

        Long currentVersion = versions.get(0);
        if (command.expectedVersion() != null && !Objects.equals(currentVersion, command.expectedVersion())) {
            throw WorkspaceProjectException.planStale("Workspace version conflict");
        }

        // Atomic swap of OWNER and MEMBER roles
        jdbcTemplate.update(
                "UPDATE workspace_members SET role = 'MEMBER', updated_at = now() WHERE workspace_id = ? AND user_id = ?",
                workspaceId,
                principal.userId()
        );

        jdbcTemplate.update(
                "UPDATE workspace_members SET role = 'OWNER', updated_at = now() WHERE workspace_id = ? AND user_id = ?",
                workspaceId,
                command.targetUserId()
        );

        jdbcTemplate.update(
                "UPDATE workspaces SET owner_id = ?, version = version + 1, updated_at = now() WHERE id = ?",
                command.targetUserId(),
                workspaceId
        );
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private static String getRootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
