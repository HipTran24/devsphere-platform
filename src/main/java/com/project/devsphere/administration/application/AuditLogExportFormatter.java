package com.project.devsphere.administration.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.administration.api.dto.AdminAuditLogResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Renders audit search results as spreadsheet-safe CSV or JSON documents. */
public final class AuditLogExportFormatter {

    public enum Format { CSV, JSON }

    static final List<String> CSV_COLUMNS = List.of(
            "id", "occurredAt", "actorId", "action", "entityType", "entityId",
            "workspaceId", "projectId", "traceId");

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AuditLogExportFormatter() {
    }

    public static Format parseFormat(String value) {
        if (value == null || value.isBlank()) {
            return Format.CSV;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "csv" -> Format.CSV;
            case "json" -> Format.JSON;
            default -> throw AdminException.validationFailed("format must be one of: csv, json");
        };
    }

    public static byte[] render(List<AdminAuditLogResponse> logs, Format format) {
        Objects.requireNonNull(format, "format must not be null");
        return format == Format.JSON ? toJson(logs) : toCsv(logs).getBytes(StandardCharsets.UTF_8);
    }

    public static String toCsv(List<AdminAuditLogResponse> logs) {
        StringBuilder csv = new StringBuilder(String.join(",", CSV_COLUMNS)).append("\r\n");
        for (AdminAuditLogResponse log : logs) {
            csv.append(String.join(",",
                    cell(log.id()),
                    cell(log.occurredAt()),
                    cell(log.actorId()),
                    cell(log.action()),
                    cell(log.entityType()),
                    cell(log.entityId()),
                    cell(log.workspaceId()),
                    cell(log.projectId()),
                    cell(log.traceId())
            )).append("\r\n");
        }
        return csv.toString();
    }

    /**
     * Neutralises spreadsheet formula injection (CWE-1236): values starting with {@code = + - @},
     * tab or carriage return are prefixed with a single quote. Cells containing separators, quotes
     * or line breaks are quoted with embedded quotes doubled.
     */
    public static String sanitizeCsvCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = value;
        String trimmed = safe.stripLeading();
        if (!trimmed.isEmpty()) {
            char first = trimmed.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                safe = "'" + safe;
            }
        }
        if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            safe = '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    private static String cell(Object value) {
        return sanitizeCsvCell(value == null ? null : value.toString());
    }

    private static byte[] toJson(List<AdminAuditLogResponse> logs) {
        try {
            return JSON.writeValueAsBytes(logs);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Audit export cannot be serialised", ex);
        }
    }
}
