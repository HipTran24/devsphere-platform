package com.project.devsphere.project.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Supplier;

/** Coordinates synchronous persistence through public application ports.
 * Callbacks perform database work only. External integrations consume the outbox.
 */
@Service
public class ProjectTransactions {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public ProjectTransactions(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Map<String, Object> execute(UUID workspaceId, UUID projectId, UUID actorId,
                                       long expectedVersion, UUID idempotencyKey,
                                       String command, Map<String, Object> input,
                                       Supplier<Map<String, Object>> changes) {
        Objects.requireNonNull(workspaceId); Objects.requireNonNull(projectId);
        Objects.requireNonNull(actorId); Objects.requireNonNull(idempotencyKey);
        if (command == null || command.isBlank() || command.length() > 100) {
            throw new IllegalArgumentException("A command name of at most 100 characters is required");
        }
        jdbc.queryForObject("select set_config('lock_timeout','5s',true)", String.class);
        var projects = jdbc.queryForList("select version from projects where id=? and workspace_id=? for update", projectId, workspaceId);
        if (projects.isEmpty()) throw new IllegalArgumentException("Project not found in Workspace");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select ds_has_project_role(?,?, 'PM')", Boolean.class, projectId, actorId))) {
            throw new SecurityException("An active PM role is required");
        }
        jdbc.queryForObject("select set_config('devsphere.actor_id',?,true)", String.class, actorId.toString());
        String fingerprint = digest(encode(Map.of("actor",actorId,"command",command,"input",input,"expectedVersion",expectedVersion)));
        var existing = jdbc.queryForList("""
                select request_hash,status,result::text from background_jobs
                where workspace_id=? and project_id=? and job_kind='IDEMPOTENCY' and idempotency_key=?
                """, workspaceId, projectId, idempotencyKey.toString());
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (!fingerprint.equals(row.get("request_hash"))) throw new IllegalArgumentException("Idempotency key belongs to a different request");
            if (!"SUCCESS".equals(row.get("status"))) throw new IllegalStateException("Command is already processing");
            return decode((String)row.get("result"));
        }
        if (((Number)projects.getFirst().get("version")).longValue() != expectedVersion) {
            throw new ConcurrentModificationException("PLAN_STALE: Project changed since preview");
        }
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into background_jobs(id,workspace_id,project_id,requested_by,job_kind,idempotency_key,request_hash,payload,status,started_at)
                values (?,?,?,?,'IDEMPOTENCY',?,?,?::jsonb,'RUNNING',now())
                """, jobId, workspaceId, projectId, actorId, idempotencyKey.toString(), fingerprint,
                encode(Map.of("schemaVersion",1,"command",command)));
        var result = new LinkedHashMap<>(Objects.requireNonNull(changes.get(), "Command result"));
        jdbc.update("update background_jobs set status='SUCCESS',result=?::jsonb,finished_at=now() where id=?", encode(result), jobId);
        jdbc.update("""
                insert into background_jobs(workspace_id,project_id,requested_by,job_kind,idempotency_key,payload)
                values (?,?,?,'OUTBOX_EVENT',?,?::jsonb)
                """, workspaceId, projectId, actorId, idempotencyKey.toString(),
                encode(Map.of("schemaVersion",1,"eventType",command,"result",result)));
        return result;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Command document cannot be encoded", e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decode(String value) {
        try { return json.readValue(value, Map.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored command result is invalid", e); }
    }

    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
