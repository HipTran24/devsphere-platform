package com.project.devsphere.reporting.application;

import com.project.devsphere.reporting.domain.Report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ReportExportGenerator {

    private ReportExportGenerator() {
    }

    public static byte[] generatePdf(Report report, String projectName) {
        String title = "DevSphere Report: " + (report.getType() != null ? report.getType().name() : "SUMMARY");
        String proj = projectName != null ? projectName : "Project";
        String period = report.getPeriodStart() + " to " + report.getPeriodEnd();
        String status = report.getStatus() != null ? report.getStatus().name() : "DRAFT";
        String pub = report.getPublishedAt() != null ? report.getPublishedAt().toString() : "Not Published";

        String textStream = "BT\n" +
                "/F1 18 Tf\n" +
                "50 720 Td\n" +
                "(" + escapePdf(title) + ") Tj\n" +
                "/F1 12 Tf\n" +
                "0 -30 Td\n" +
                "(Project: " + escapePdf(proj) + ") Tj\n" +
                "0 -20 Td\n" +
                "(Type: " + escapePdf(report.getType() != null ? report.getType().name() : "") + ") Tj\n" +
                "0 -20 Td\n" +
                "(Status: " + escapePdf(status) + ") Tj\n" +
                "0 -20 Td\n" +
                "(Period: " + escapePdf(period) + ") Tj\n" +
                "0 -20 Td\n" +
                "(Published: " + escapePdf(pub) + ") Tj\n" +
                "ET\n";

        byte[] streamBytes = textStream.getBytes(StandardCharsets.US_ASCII);

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
            String proj = escapeXml(projectName != null ? projectName : "Project");
            String type = escapeXml(report.getType() != null ? report.getType().name() : "");
            String status = escapeXml(report.getStatus() != null ? report.getStatus().name() : "DRAFT");
            String start = escapeXml(report.getPeriodStart() != null ? report.getPeriodStart().toString() : "");
            String end = escapeXml(report.getPeriodEnd() != null ? report.getPeriodEnd().toString() : "");
            String publishedAt = escapeXml(report.getPublishedAt() != null ? report.getPublishedAt().toString() : "N/A");

            zos.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            String sheetXml = """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData>
                        <row r="1">
                          <c r="A1" t="inlineStr"><is><t>Attribute</t></is></c>
                          <c r="B1" t="inlineStr"><is><t>Value</t></is></c>
                        </row>
                        <row r="2">
                          <c r="A2" t="inlineStr"><is><t>Project</t></is></c>
                          <c r="B2" t="inlineStr"><is><t>%s</t></is></c>
                        </row>
                        <row r="3">
                          <c r="A3" t="inlineStr"><is><t>Report Type</t></is></c>
                          <c r="B3" t="inlineStr"><is><t>%s</t></is></c>
                        </row>
                        <row r="4">
                          <c r="A4" t="inlineStr"><is><t>Status</t></is></c>
                          <c r="B4" t="inlineStr"><is><t>%s</t></is></c>
                        </row>
                        <row r="5">
                          <c r="A5" t="inlineStr"><is><t>Period Start</t></is></c>
                          <c r="B5" t="inlineStr"><is><t>%s</t></is></c>
                        </row>
                        <row r="6">
                          <c r="A6" t="inlineStr"><is><t>Period End</t></is></c>
                          <c r="B6" t="inlineStr"><is><t>%s</t></is></c>
                        </row>
                        <row r="7">
                          <c r="A7" t="inlineStr"><is><t>Published At</t></is></c>
                          <c r="B7" t="inlineStr"><is><t>%s</t></is></c>
                        </row>
                      </sheetData>
                    </worksheet>
                    """.formatted(proj, type, status, start, end, publishedAt);
            zos.write(sheetXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate XLSX", e);
        }

        return baos.toByteArray();
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
                .replace("'", "&apos;");
    }
}
