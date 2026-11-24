package com.project.devsphere.reporting.application;

import com.project.devsphere.reporting.domain.Report;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReportExportGeneratorContentTest {

    @Test
    @DisplayName("generatePdf renders real report content with valid %PDF-1.4 header, exact xref offsets, and %%EOF trailer")
    void generatePdfRendersRealContentAndExactXref() {
        Report report = new Report();
        report.setType(Report.Type.SPRINT);
        report.setStatus(Report.Status.PUBLISHED);
        report.setPeriodStart(LocalDate.of(2026, 11, 1));
        report.setPeriodEnd(LocalDate.of(2026, 11, 14));
        report.setPublishedAt(Instant.parse("2026-11-15T10:00:00Z"));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("velocityPoints", 42);
        metrics.put("openDefects", 2);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("summary", "Sprint 8 delivered core storage verification (with parentheses & \\backslash) and real reporting.");
        content.put("metrics", metrics);
        content.put("highlights", List.of("SigV4 HEAD verification enabled", "CWE-1236 formula protection added"));
        report.setContent(content);

        byte[] pdfBytes = ReportExportGenerator.generatePdf(report, "DevSphere Core");
        String pdf = new String(pdfBytes, StandardCharsets.US_ASCII);

        assertThat(pdf).startsWith("%PDF-1.4\n");
        assertThat(pdf).endsWith("%%EOF\n");
        assertThat(pdf).contains("Sprint 8 delivered core storage verification");
        assertThat(pdf).contains("metrics.velocityPoints: 42");
        assertThat(pdf).contains("metrics.openDefects: 2");
        assertThat(pdf).contains("highlights[1]: SigV4 HEAD verification enabled");
        assertThat(pdf).contains("\\(with parentheses &");
        assertThat(pdf).contains("\\\\backslash\\)");

        // Verify exact xref offset table against actual byte offsets in PDF buffer
        int startXrefMarker = pdf.lastIndexOf("startxref\n");
        assertThat(startXrefMarker).isPositive();
        int eofMarker = pdf.indexOf("\n%%EOF", startXrefMarker);
        int xrefOffset = Integer.parseInt(pdf.substring(startXrefMarker + "startxref\n".length(), eofMarker).trim());
        assertThat(pdf.substring(xrefOffset)).startsWith("xref\n0 6\n");

        String xrefSection = pdf.substring(xrefOffset);
        String[] lines = xrefSection.split("\n");
        assertThat(lines[2]).isEqualTo("0000000000 65535 f ");
        for (int objNum = 1; objNum <= 5; objNum++) {
            String entry = lines[2 + objNum];
            assertThat(entry).matches("^\\d{10} 00000 n $");
            int objOffset = Integer.parseInt(entry.substring(0, 10));
            String expectedHeader = objNum + " 0 obj\n";
            assertThat(new String(pdfBytes, objOffset, expectedHeader.length(), StandardCharsets.US_ASCII))
                    .isEqualTo(expectedHeader);
        }
    }

    @Test
    @DisplayName("generatePdf wraps long lines and truncates cleanly within page bounds")
    void generatePdfWrapsAndTruncatesLongContent() {
        Report report = new Report();
        report.setType(Report.Type.WEEKLY);
        report.setStatus(Report.Status.DRAFT);
        report.setPeriodStart(LocalDate.of(2026, 11, 1));
        report.setPeriodEnd(LocalDate.of(2026, 11, 7));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("summary", "Long summary ".repeat(40));
        List<String> highlights = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            highlights.add("Highlight item number " + i + " with extra details for wrapping verification");
        }
        content.put("highlights", highlights);
        report.setContent(content);

        byte[] pdfBytes = ReportExportGenerator.generatePdf(report, "Alpha");
        String pdf = new String(pdfBytes, StandardCharsets.US_ASCII);
        assertThat(pdf).startsWith("%PDF-1.4\n").endsWith("%%EOF\n");
        long tjCount = pdf.lines().filter(l -> l.endsWith(" Tj")).count();
        assertThat(tjCount).isLessThanOrEqualTo(45L);
    }

    @Test
    @DisplayName("generateXlsx renders real content rows and applies CWE-1236 formula neutralization")
    void generateXlsxRendersContentAndNeutralizesFormulas() throws Exception {
        assertThat(ReportExportGenerator.neutralizeFormula("=CMD()")).isEqualTo("'=CMD()");
        assertThat(ReportExportGenerator.neutralizeFormula("+1+1")).isEqualTo("'+1+1");
        assertThat(ReportExportGenerator.neutralizeFormula("-2+3")).isEqualTo("'-2+3");
        assertThat(ReportExportGenerator.neutralizeFormula("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(ReportExportGenerator.neutralizeFormula("  =HYPERLINK(\"http://evil\")")).isEqualTo("'  =HYPERLINK(\"http://evil\")");
        assertThat(ReportExportGenerator.neutralizeFormula("\t=1+1")).isEqualTo("'\t=1+1");
        assertThat(ReportExportGenerator.neutralizeFormula("\r=1+1")).isEqualTo("'\r=1+1");
        assertThat(ReportExportGenerator.neutralizeFormula("Safe value")).isEqualTo("Safe value");

        Report report = new Report();
        report.setType(Report.Type.RISK);
        report.setStatus(Report.Status.PUBLISHED);
        report.setPeriodStart(LocalDate.of(2026, 11, 1));
        report.setPeriodEnd(LocalDate.of(2026, 11, 7));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("cmdPayload", "=CMD()");
        metrics.put("plusPayload", "+1+1");
        metrics.put("minusPayload", "-2+3");
        metrics.put("atPayload", "@SUM(A1)");
        metrics.put("spacePayload", "  =HYPERLINK(\"https://example.test\")");
        metrics.put("tabPayload", "\t=1+1");
        metrics.put("crPayload", "\r=1+1");
        metrics.put("cleanCount", 19);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("summary", "Risk assessment with neutralized payloads");
        content.put("metrics", metrics);
        report.setContent(content);

        byte[] xlsxBytes = ReportExportGenerator.generateXlsx(report, "=CMD('project')");

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xlsxBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        assertThat(entries).containsKey("xl/worksheets/sheet1.xml");

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(entries.get("xl/worksheets/sheet1.xml")));
        NodeList textNodes = doc.getElementsByTagName("t");
        List<String> cellTexts = new ArrayList<>();
        for (int i = 0; i < textNodes.getLength(); i++) {
            cellTexts.add(textNodes.item(i).getTextContent());
        }

        assertThat(cellTexts).contains(
                "'=CMD('project')",
                "Summary",
                "Risk assessment with neutralized payloads",
                "metrics.cleanCount",
                "19",
                "'=CMD()",
                "'+1+1",
                "'-2+3",
                "'@SUM(A1)",
                "'  =HYPERLINK(\"https://example.test\")",
                "'\t=1+1",
                "'\r=1+1"
        );
    }
}
