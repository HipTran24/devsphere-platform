package com.project.devsphere.reporting.application;

import com.project.devsphere.reporting.domain.Report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ReportExportGenerator {

    private static final int PDF_LINE_WRAP = 84;
    private static final int PDF_MAX_CONTENT_LINES = 36;

    private ReportExportGenerator() {
    }

    public record ContentRow(String label, String value) {}

    public static byte[] generatePdf(Report report, String projectName) {
        String title = "DevSphere Report: " + (report.getType() != null ? report.getType().name() : "SUMMARY");
        String proj = projectName != null ? projectName : "Project";
        String type = report.getType() != null ? report.getType().name() : "";
        String period = report.getPeriodStart() + " to " + report.getPeriodEnd();
        String status = report.getStatus() != null ? report.getStatus().name() : "DRAFT";
        String pub = report.getPublishedAt() != null ? report.getPublishedAt().toString() : "Not Published";

        StringBuilder textStream = new StringBuilder();
        textStream.append("BT\n");
        textStream.append("/F1 16 Tf\n");
        textStream.append("50 740 Td\n");
        textStream.append("(").append(escapePdf(sanitizePdfAscii(title))).append(") Tj\n");
        textStream.append("/F1 11 Tf\n");
        textStream.append("0 -24 Td\n");
        textStream.append("(").append(escapePdf(sanitizePdfAscii("Project: " + proj))).append(") Tj\n");
        textStream.append("0 -16 Td\n");
        textStream.append("(").append(escapePdf(sanitizePdfAscii("Type: " + type))).append(") Tj\n");
        textStream.append("0 -16 Td\n");
        textStream.append("(").append(escapePdf(sanitizePdfAscii("Status: " + status))).append(") Tj\n");
        textStream.append("0 -16 Td\n");
        textStream.append("(").append(escapePdf(sanitizePdfAscii("Period: " + period))).append(") Tj\n");
        textStream.append("0 -16 Td\n");
        textStream.append("(").append(escapePdf(sanitizePdfAscii("Published: " + pub))).append(") Tj\n");

        List<String> contentLines = buildPdfContentLines(report.getContent());
        if (!contentLines.isEmpty()) {
            textStream.append("0 -10 Td\n");
            textStream.append("/F1 10 Tf\n");
            for (String line : contentLines) {
                textStream.append("0 -14 Td\n");
                textStream.append("(").append(escapePdf(line)).append(") Tj\n");
            }
        }
        textStream.append("ET\n");

        byte[] streamBytes = textStream.toString().getBytes(StandardCharsets.US_ASCII);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            buf.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));

            long offset1 = buf.size();
            buf.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));

            long offset2 = buf.size();
            buf.write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));

            long offset3 = buf.size();
            buf.write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));

            long offset4 = buf.size();
            buf.write("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));

            long offset5 = buf.size();
            String obj5Head = "5 0 obj\n<< /Length " + streamBytes.length + " >>\nstream\n";
            buf.write(obj5Head.getBytes(StandardCharsets.US_ASCII));
            buf.write(streamBytes);
            buf.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));

            long xrefOffset = buf.size();
            String xref = String.format(
                    Locale.ROOT,
                    "xref\n0 6\n0000000000 65535 f \n%010d 00000 n \n%010d 00000 n \n%010d 00000 n \n%010d 00000 n \n%010d 00000 n \n" +
                            "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n",
                    offset1, offset2, offset3, offset4, offset5, xrefOffset
            );
            buf.write(xref.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate PDF", e);
        }

        return buf.toByteArray();
    }

    public static byte[] generateXlsx(Report report, String projectName) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {

            // 1. [Content_Types].xml
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>
                    """.stripIndent().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 2. _rels/.rels
            zos.putNextEntry(new ZipEntry("_rels/.rels"));
            zos.write("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """.stripIndent().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 3. xl/_rels/workbook.xml.rels
            zos.putNextEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));
            zos.write("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>
                    """.stripIndent().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 4. xl/workbook.xml
            zos.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zos.write("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets>
                        <sheet name="Report" sheetId="1" r:id="rId1"/>
                      </sheets>
                    </workbook>
                    """.stripIndent().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 5. xl/worksheets/sheet1.xml
            List<ContentRow> rows = new ArrayList<>();
            rows.add(new ContentRow("Project", projectName != null ? projectName : "Project"));
            rows.add(new ContentRow("Report Type", report.getType() != null ? report.getType().name() : ""));
            rows.add(new ContentRow("Status", report.getStatus() != null ? report.getStatus().name() : "DRAFT"));
            rows.add(new ContentRow("Period Start", report.getPeriodStart() != null ? report.getPeriodStart().toString() : ""));
            rows.add(new ContentRow("Period End", report.getPeriodEnd() != null ? report.getPeriodEnd().toString() : ""));
            rows.add(new ContentRow("Published At", report.getPublishedAt() != null ? report.getPublishedAt().toString() : "N/A"));
            rows.addAll(extractContentRows(report.getContent()));

            StringBuilder sheetXml = new StringBuilder();
            sheetXml.append("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData>
                        <row r="1">
                          <c r="A1" t="inlineStr"><is><t>Attribute</t></is></c>
                          <c r="B1" t="inlineStr"><is><t>Value</t></is></c>
                        </row>
                    """);

            int rowNumber = 2;
            for (ContentRow row : rows) {
                String cellA = formatSafeXmlCell(row.label());
                String cellB = formatSafeXmlCell(row.value());
                sheetXml.append("    <row r=\"").append(rowNumber).append("\">\n");
                sheetXml.append("      <c r=\"A").append(rowNumber).append("\" t=\"inlineStr\"><is><t>").append(cellA).append("</t></is></c>\n");
                sheetXml.append("      <c r=\"B").append(rowNumber).append("\" t=\"inlineStr\"><is><t>").append(cellB).append("</t></is></c>\n");
                sheetXml.append("    </row>\n");
                rowNumber++;
            }

            sheetXml.append("  </sheetData>\n</worksheet>\n");

            zos.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zos.write(sheetXml.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate XLSX", e);
        }

        return baos.toByteArray();
    }

    /**
     * CWE-1236 formula neutralization: prefixes any cell value (or leading-whitespace-stripped
     * value) starting with {@code =}, {@code +}, {@code -}, {@code @}, {@code \t}, or {@code \r}
     * with a single quote {@code '} before XML escaping.
     */
    public static String neutralizeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (startsWithFormulaTrigger(value) || startsWithFormulaTrigger(value.stripLeading())) {
            return "'" + value;
        }
        return value;
    }

    static List<ContentRow> extractContentRows(Map<String, Object> content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<ContentRow> rows = new ArrayList<>();
        Set<String> handled = new HashSet<>();
        handled.add("schemaVersion");

        for (String summaryKey : List.of("summary", "executive_summary", "executiveSummary")) {
            if (content.containsKey(summaryKey) && content.get(summaryKey) != null) {
                flattenEntry("Summary", content.get(summaryKey), rows);
                handled.add(summaryKey);
            }
        }

        if (content.containsKey("metrics") && content.get("metrics") != null) {
            flattenEntry("metrics", content.get("metrics"), rows);
            handled.add("metrics");
        }

        for (String listKey : List.of("highlights", "facts", "sections")) {
            if (content.containsKey(listKey) && content.get(listKey) != null) {
                flattenEntry(listKey, content.get(listKey), rows);
                handled.add(listKey);
            }
        }

        List<String> remainingKeys = new ArrayList<>();
        for (String key : content.keySet()) {
            if (!handled.contains(key)) {
                remainingKeys.add(key);
            }
        }
        Collections.sort(remainingKeys);
        for (String key : remainingKeys) {
            Object value = content.get(key);
            if (value != null) {
                flattenEntry(key, value, rows);
            }
        }
        return rows;
    }

    private static void flattenEntry(String prefix, Object value, List<ContentRow> out) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object k : map.keySet()) {
                if (k != null) {
                    keys.add(String.valueOf(k));
                }
            }
            Collections.sort(keys);
            for (String key : keys) {
                Object child = map.get(key);
                if (child != null) {
                    flattenEntry(prefix + "." + key, child, out);
                }
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            int idx = 1;
            for (Object item : collection) {
                if (item != null) {
                    flattenEntry(prefix + "[" + idx + "]", item, out);
                }
                idx++;
            }
            return;
        }
        out.add(new ContentRow(prefix, String.valueOf(value)));
    }

    private static List<String> buildPdfContentLines(Map<String, Object> content) {
        List<ContentRow> rows = extractContentRows(content);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> wrapped = new ArrayList<>();
        for (ContentRow row : rows) {
            String fullLine = sanitizePdfAscii(row.label() + ": " + row.value());
            wrapLine(fullLine, PDF_LINE_WRAP, wrapped);
            if (wrapped.size() >= PDF_MAX_CONTENT_LINES) {
                break;
            }
        }
        if (wrapped.size() > PDF_MAX_CONTENT_LINES) {
            List<String> bounded = new ArrayList<>(wrapped.subList(0, PDF_MAX_CONTENT_LINES));
            String last = bounded.get(PDF_MAX_CONTENT_LINES - 1);
            if (last.length() > PDF_LINE_WRAP - 3) {
                last = last.substring(0, PDF_LINE_WRAP - 3);
            }
            bounded.set(PDF_MAX_CONTENT_LINES - 1, last + "...");
            return bounded;
        }
        return wrapped;
    }

    private static void wrapLine(String text, int maxWidth, List<String> out) {
        if (text.isEmpty()) {
            return;
        }
        int start = 0;
        while (start < text.length()) {
            if (text.length() - start <= maxWidth) {
                out.add(text.substring(start));
                break;
            }
            int end = start + maxWidth;
            int space = text.lastIndexOf(' ', end);
            if (space > start) {
                out.add(text.substring(start, space));
                start = space + 1;
            } else {
                out.add(text.substring(start, end));
                start = end;
            }
        }
    }

    private static boolean startsWithFormulaTrigger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }

    private static String formatSafeXmlCell(String raw) {
        return escapeXml(sanitizeXmlChars(neutralizeFormula(raw)));
    }

    private static String sanitizeXmlChars(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static String sanitizePdfAscii(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                sb.append(' ');
            } else if (c >= 32 && c <= 126) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static String escapePdf(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replace("\r", "&#13;");
    }
}
