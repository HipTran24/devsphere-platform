package com.project.devsphere.reporting.api;

import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.reporting.api.dto.CreateReportRequest;
import com.project.devsphere.reporting.api.dto.ReportResponse;
import com.project.devsphere.reporting.application.ReportException;
import com.project.devsphere.reporting.application.ReportService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/workspaces/{workspaceId}/projects/{projectId}/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = Objects.requireNonNull(reportService, "reportService must not be null");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReportResponse> createReport(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateReportRequest request) {

        ReportResponse response = reportService.createReport(principal, workspaceId, projectId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/reports/" + response.id()))
                .body(response);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ReportResponse>> getReports(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String type) {

        List<ReportResponse> response = reportService.getReports(principal, workspaceId, projectId, type);
        return ResponseEntity.ok(response);
    }

    @GetMapping(path = "/{reportId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReportResponse> getReport(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID reportId) {

        ReportResponse response = reportService.getReport(principal, workspaceId, projectId, reportId);
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/{reportId}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReportResponse> approveReport(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID reportId) {

        ReportResponse response = reportService.approveReport(principal, workspaceId, projectId, reportId);
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/{reportId}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReportResponse> publishReport(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID reportId) {

        ReportResponse response = reportService.publishReport(principal, workspaceId, projectId, reportId);
        return ResponseEntity.ok(response);
    }

    @GetMapping(path = "/{reportId}/export")
    public ResponseEntity<byte[]> exportReport(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID reportId,
            @RequestParam(defaultValue = "PDF") String format) {

        byte[] fileBytes = reportService.exportReport(principal, workspaceId, projectId, reportId, format);
        String normFormat = format != null ? format.trim().toUpperCase() : "PDF";

        MediaType mediaType;
        String extension;
        if ("XLSX".equals(normFormat)) {
            mediaType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            extension = "xlsx";
        } else {
            mediaType = MediaType.APPLICATION_PDF;
            extension = "pdf";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("report-" + reportId + "." + extension)
                .build());

        return new ResponseEntity<>(fileBytes, headers, HttpStatus.OK);
    }

    @ExceptionHandler(ReportException.class)
    public ResponseEntity<ProblemDetail> handleReportException(ReportException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getStatus().getReasonPhrase());
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");
        problem.setProperty("code", "ACCESS_DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Validation failed");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        problem.setTitle("Bad Request");
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Database constraint violation");
        problem.setTitle("Conflict");
        problem.setProperty("code", "DATA_INTEGRITY_VIOLATION");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
