package com.project.devsphere.administration.api;

import com.project.devsphere.administration.api.dto.AdminAuditLogResponse;
import com.project.devsphere.administration.api.dto.AdminPageResponse;
import com.project.devsphere.administration.api.dto.AdminUserResponse;
import com.project.devsphere.administration.api.dto.UserStatusUpdateRequest;
import com.project.devsphere.administration.application.AccountAdministrationService;
import com.project.devsphere.administration.application.AdminException;
import com.project.devsphere.administration.application.AuditLogExportFormatter;
import com.project.devsphere.administration.application.AuditLogService;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.User;
import com.project.devsphere.operations.api.dto.AuditSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AccountAdministrationController {

    private static final DateTimeFormatter EXPORT_FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final AccountAdministrationService accountAdministrationService;
    private final AccessPolicy accessPolicy;
    private final AuditLogService auditLogService;

    public AccountAdministrationController(
            AccountAdministrationService accountAdministrationService,
            AccessPolicy accessPolicy) {
        this(accountAdministrationService, accessPolicy, null);
    }

    @Autowired
    public AccountAdministrationController(
            AccountAdministrationService accountAdministrationService,
            AccessPolicy accessPolicy,
            @Autowired(required = false) AuditLogService auditLogService) {
        this.accountAdministrationService = accountAdministrationService;
        this.accessPolicy = accessPolicy;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/users")
    public ResponseEntity<AdminPageResponse<AdminUserResponse>> getUsers(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) User.Status status,
            @RequestParam(required = false) User.SystemRole role,
            @RequestParam(required = false) User.SystemRole systemRole,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {

        accessPolicy.requireSystemAdmin(principal);

        String search = (q != null && !q.isBlank()) ? q : keyword;
        if (search != null && search.contains("%")) {
            try {
                search = java.net.URLDecoder.decode(search, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        User.SystemRole targetRole = role != null ? role : systemRole;

        AdminPageResponse<AdminUserResponse> response = accountAdministrationService.getUsers(
                page, size, search, status, targetRole, sort
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PatchMapping("/users/{userId}/status")
    public ResponseEntity<AdminUserResponse> updateUserStatus(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID userId,
            @Valid @RequestBody UserStatusUpdateRequest request) {

        accessPolicy.requireSystemAdmin(principal);

        AdminUserResponse response = accountAdministrationService.updateUserStatus(
                principal, userId, request
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<AdminPageResponse<AdminAuditLogResponse>> getAuditLogs(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String traceId,
            @RequestParam(defaultValue = "occurredAt,desc") String sort) {

        accessPolicy.requireSystemAdmin(principal);

        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");

        AdminPageResponse<AdminAuditLogResponse> response = accountAdministrationService.getAuditLogs(
                page, size,
                new AccountAdministrationService.AuditLogFilter(
                        actorId, action, entityType, workspaceId, projectId, entityId, traceId, fromInstant, toInstant),
                sort
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/audit-logs/summary")
    public ResponseEntity<AuditSummaryResponse> getAuditLogSummary(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        accessPolicy.requireSystemAdmin(principal);

        AuditSummaryResponse response = accountAdministrationService.summarizeAuditLogs(
                parseInstant(from, "from"), parseInstant(to, "to"));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/audit-logs/export")
    public ResponseEntity<byte[]> exportAuditLogs(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String traceId) {

        accessPolicy.requireSystemAdmin(principal);

        AuditLogExportFormatter.Format exportFormat = AuditLogExportFormatter.parseFormat(format);
        List<AdminAuditLogResponse> logs = accountAdministrationService.exportAuditLogs(
                new AccountAdministrationService.AuditLogFilter(
                        actorId, action, entityType, workspaceId, projectId, entityId, traceId,
                        parseInstant(from, "from"), parseInstant(to, "to")));
        byte[] body = AuditLogExportFormatter.render(logs, exportFormat);

        if (auditLogService != null) {
            auditLogService.record(principal.userId(), "AUDIT_LOG_EXPORTED", "audit_log", null,
                    Map.of("format", exportFormat.name(), "rows", logs.size()));
        }

        boolean json = exportFormat == AuditLogExportFormatter.Format.JSON;
        String filename = "audit-logs-" + EXPORT_FILE_TIMESTAMP.format(Instant.now()) + (json ? ".json" : ".csv");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(json ? MediaType.APPLICATION_JSON : new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    private Instant parseInstant(String value, String paramName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw AdminException.validationFailed("Invalid ISO-8601 timestamp for parameter '" + paramName + "': " + value);
        }
    }

    @ExceptionHandler(AdminException.class)
    public ResponseEntity<ProblemDetail> handleAdminException(AdminException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getCode());
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(ex.getStatus())
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("FORBIDDEN");
        problem.setProperty("code", "FORBIDDEN");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ProblemDetail> handleValidationExceptions(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("VALIDATION_FAILED");
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}
