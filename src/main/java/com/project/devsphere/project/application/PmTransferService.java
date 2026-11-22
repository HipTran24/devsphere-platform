package com.project.devsphere.project.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class PmTransferService {

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;

    public PmTransferService(JdbcTemplate jdbcTemplate, AccessPolicy accessPolicy) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    /**
     * Proposes a PM assignment to an eligible candidate.
     * Guarded by AccessPolicy.requireWorkspaceOwner.
     * Continuous coverage: Outgoing PM retains full operational PM authority while nomination is PENDING.
     */
    @Transactional
    public PmAssignmentDetails proposePmAssignment(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            ProposePmCommand command) {
        accessPolicy.requireWorkspaceOwner(principal, workspaceId);

        if (command == null || command.candidateUserId() == null || command.expectedVersion() == null) {
            throw WorkspaceProjectException.validationFailed("Candidate user id and expected version must be provided");
        }

        UUID assignmentId = UUID.randomUUID();

        try {
            jdbcTemplate.execute((java.sql.Connection conn) -> {
                try (var cs = conn.prepareCall("SELECT ds_propose_pm_assignment(?, ?, ?, ?, ?)")) {
                    cs.setObject(1, projectId);
                    cs.setObject(2, principal.userId());
                    cs.setObject(3, command.candidateUserId());
                    cs.setObject(4, assignmentId);
                    cs.setLong(5, command.expectedVersion());
                    cs.execute();
                    return null;
                }
            });
        } catch (DataAccessException e) {
            handleProposeException(e);
        }

        return getCurrentPmAssignment(principal, workspaceId, projectId)
                .orElseThrow(() -> new IllegalStateException("Proposed PM assignment not found after persistence"));
    }

    /**
     * Retrieves current PM assignment proposal facts for a project.
     * Guarded by workspace membership.
     */
    @Transactional(readOnly = true)
    public Optional<PmAssignmentDetails> getCurrentPmAssignment(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {
        accessPolicy.requireWorkspaceMember(principal, workspaceId);

        String sql = """
                SELECT settings#>>'{pmAssignment,id}' AS id,
                       settings#>>'{pmAssignment,candidateUserId}' AS candidate_user_id,
                       settings#>>'{pmAssignment,proposedBy}' AS proposed_by,
                       settings#>>'{pmAssignment,proposedAt}' AS proposed_at,
                       settings#>>'{pmAssignment,expiresAt}' AS expires_at,
                       settings#>>'{pmAssignment,status}' AS status
                FROM projects
                WHERE id = ? AND workspace_id = ?
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, projectId, workspaceId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> row = rows.get(0);
        String idStr = (String) row.get("id");
        if (idStr == null || idStr.isBlank()) {
            return Optional.empty();
        }

        UUID id = UUID.fromString(idStr);
        UUID candidateUserId = UUID.fromString((String) row.get("candidate_user_id"));
        UUID proposedBy = UUID.fromString((String) row.get("proposed_by"));
        Instant proposedAt = OffsetDateTime.parse((String) row.get("proposed_at")).toInstant();
        Instant expiresAt = OffsetDateTime.parse((String) row.get("expires_at")).toInstant();
        String status = (String) row.get("status");

        return Optional.of(new PmAssignmentDetails(
                id,
                candidateUserId,
                proposedBy,
                proposedAt,
                expiresAt,
                status
        ));
    }

    /**
     * Accepts a proposed PM assignment by the nominated candidate.
     * Executes ds_accept_pm_assignment to atomically transition the PM role,
     * preserve outgoing PM's non-PM roles, and activate workspace if SETUP_INCOMPLETE.
     */
    @Transactional
    public void acceptPmAssignment(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID assignmentId,
            AcceptPmCommand command) {
        accessPolicy.requireAuthenticatedUser(principal);

        if (command == null || command.expectedVersion() == null) {
            throw WorkspaceProjectException.validationFailed("Expected version must be provided");
        }

        // Validate that caller is the designated candidate
        String candidateSql = """
                SELECT settings#>>'{pmAssignment,candidateUserId}' AS candidate_user_id
                FROM projects
                WHERE id = ? AND workspace_id = ?
                """;
        List<String> candidates = jdbcTemplate.query(candidateSql, (rs, rowNum) -> rs.getString("candidate_user_id"), projectId, workspaceId);
        if (candidates.isEmpty()) {
            throw WorkspaceProjectException.notFound("Project not found in designated workspace");
        }

        String candidateIdStr = candidates.get(0);
        if (candidateIdStr == null || !principal.userId().toString().equalsIgnoreCase(candidateIdStr)) {
            throw WorkspaceProjectException.accessDenied("Only the nominated candidate can accept the PM assignment");
        }

        try {
            jdbcTemplate.execute((java.sql.Connection conn) -> {
                try (var cs = conn.prepareCall("SELECT ds_accept_pm_assignment(?, ?, ?, ?)")) {
                    cs.setObject(1, projectId);
                    cs.setObject(2, principal.userId());
                    cs.setObject(3, assignmentId);
                    cs.setLong(4, command.expectedVersion());
                    cs.execute();
                    return null;
                }
            });
        } catch (DataAccessException e) {
            handleAcceptException(e);
        }

        // Transition workspace to ACTIVE if SETUP_INCOMPLETE
        jdbcTemplate.update(
                "UPDATE workspaces SET status = 'ACTIVE', updated_at = now() WHERE id = ? AND status = 'SETUP_INCOMPLETE'",
                workspaceId
        );
    }

    /**
     * Declines or cancels a PM assignment proposal.
     */
    @Transactional
    public void declinePmAssignment(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID assignmentId) {
        accessPolicy.requireWorkspaceMember(principal, workspaceId);

        String verifySql = """
                SELECT settings#>>'{pmAssignment,id}' AS id,
                       settings#>>'{pmAssignment,candidateUserId}' AS candidate_id,
                       settings#>>'{pmAssignment,proposedBy}' AS proposed_by
                FROM projects
                WHERE id = ? AND workspace_id = ?
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(verifySql, projectId, workspaceId);
        if (rows.isEmpty()) {
            throw WorkspaceProjectException.notFound("Project not found");
        }

        Map<String, Object> row = rows.get(0);
        String idStr = (String) row.get("id");
        if (idStr == null || !assignmentId.toString().equalsIgnoreCase(idStr)) {
            throw WorkspaceProjectException.notFound("PM assignment not found");
        }

        String candidateId = (String) row.get("candidate_id");
        String proposedBy = (String) row.get("proposed_by");
        String callerId = principal.userId().toString();

        if (!callerId.equalsIgnoreCase(candidateId) && !callerId.equalsIgnoreCase(proposedBy)) {
            throw WorkspaceProjectException.accessDenied("Only the candidate or proposer can decline this PM assignment");
        }

        jdbcTemplate.update("""
                UPDATE projects
                SET settings = jsonb_set(
                        settings,
                        '{pmAssignment,status}',
                        '"DECLINED"',
                        false
                    ),
                    updated_at = now()
                WHERE id = ?
                """, projectId);
    }

    private void handleProposeException(DataAccessException e) {
        String msg = getRootMessage(e);
        if (msg != null) {
            if (msg.contains("Project version conflict")) {
                throw WorkspaceProjectException.planStale("Project version conflict");
            }
            if (msg.contains("A PM assignment is already pending")) {
                throw WorkspaceProjectException.duplicate("A PM assignment is already pending");
            }
            if (msg.contains("PM candidate is ineligible")) {
                throw WorkspaceProjectException.validationFailed("PM candidate is ineligible");
            }
            if (msg.contains("PM nomination requires active OWNER")) {
                throw WorkspaceProjectException.accessDenied("PM nomination requires active OWNER");
            }
        }
        throw e;
    }

    private void handleAcceptException(DataAccessException e) {
        String msg = getRootMessage(e);
        if (msg != null) {
            if (msg.contains("Project version conflict")) {
                throw WorkspaceProjectException.planStale("Project version conflict");
            }
            if (msg.contains("PM invitation is invalid or expired")) {
                throw WorkspaceProjectException.pmTransferNotAccepted("PM invitation is invalid or expired");
            }
            if (msg.contains("PM candidate is no longer eligible")) {
                throw WorkspaceProjectException.accessDenied("PM candidate is no longer eligible");
            }
            if (msg.contains("PM nomination must be renewed after OWNER transfer")) {
                throw WorkspaceProjectException.pmTransferNotAccepted("PM nomination must be renewed after OWNER transfer");
            }
        }
        throw e;
    }

    private static String getRootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
