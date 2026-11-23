package com.project.devsphere.reporting.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.reporting.api.dto.CreateReportRequest;
import com.project.devsphere.reporting.api.dto.ReportResponse;
import com.project.devsphere.reporting.domain.Report;
import com.project.devsphere.reporting.infrastructure.persistence.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private AccessPolicy accessPolicy;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ReportService reportService;

    private SessionService.Principal pmPrincipal;
    private SessionService.Principal clientPrincipal;
    private UUID workspaceId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        reportService = new ReportService(reportRepository, accessPolicy, jdbcTemplate);
        pmPrincipal = new SessionService.Principal(UUID.randomUUID(), UUID.randomUUID(), "PM User", "USER");
        clientPrincipal = new SessionService.Principal(UUID.randomUUID(), UUID.randomUUID(), "Client User", "USER");
        workspaceId = UUID.randomUUID();
        projectId = UUID.randomUUID();

        when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("createReport creates report in DRAFT status with visibleToClient = false")
    void createReportDraftSuccess() {
        ProjectScope scope = new ProjectScope(workspaceId, projectId, pmPrincipal.userId(), Set.of(ProjectRole.PM));
        when(accessPolicy.requireAnyProjectRole(eq(pmPrincipal), eq(workspaceId), eq(projectId), anySet())).thenReturn(scope);

        CreateReportRequest request = new CreateReportRequest(
                "WEEKLY",
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 7),
                Map.of("summary", "Weekly sprint progress"),
                null,
                null
        );

        ReportResponse response = reportService.createReport(pmPrincipal, workspaceId, projectId, request);

        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(response.type()).isEqualTo("WEEKLY");
        assertThat(response.visibleToClient()).isFalse();
        assertThat(response.generatedBy()).isEqualTo(pmPrincipal.userId());
        assertThat(response.approvedBy()).isNull();
    }

    @Test
    @DisplayName("createReport with unvalidated aiJobId throws ReportException")
    void createReportWithUnvalidatedAiJobRejects() {
        ProjectScope scope = new ProjectScope(workspaceId, projectId, pmPrincipal.userId(), Set.of(ProjectRole.PM));
        when(accessPolicy.requireAnyProjectRole(eq(pmPrincipal), eq(workspaceId), eq(projectId), anySet())).thenReturn(scope);

        UUID aiJobId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(contains("FROM ai_jobs"), eq(Integer.class), eq(aiJobId), eq(projectId)))
                .thenReturn(0);

        CreateReportRequest request = new CreateReportRequest(
                "WEEKLY",
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 7),
                Map.of("summary", "AI summarized report"),
                null,
                aiJobId
        );

        assertThatThrownBy(() -> reportService.createReport(pmPrincipal, workspaceId, projectId, request))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("AI report requires validated output");
    }

    @Test
    @DisplayName("approveReport requires PM role and updates status to APPROVED")
    void approveReportSuccess() {
        UUID reportId = UUID.randomUUID();
        ProjectScope scope = new ProjectScope(workspaceId, projectId, pmPrincipal.userId(), Set.of(ProjectRole.PM));
        when(accessPolicy.requireProjectRole(pmPrincipal, workspaceId, projectId, ProjectRole.PM)).thenReturn(scope);

        Report report = new Report();
        report.setId(reportId);
        report.setProjectId(projectId);
        report.setStatus(Report.Status.DRAFT);
        when(reportRepository.findByIdAndProjectId(reportId, projectId)).thenReturn(Optional.of(report));

        ReportResponse response = reportService.approveReport(pmPrincipal, workspaceId, projectId, reportId);

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.approvedBy()).isEqualTo(pmPrincipal.userId());
    }

    @Test
    @DisplayName("publishReport updates status to PUBLISHED, stamps publishedAt, and sets visibleToClient = true")
    void publishReportSuccess() {
        UUID reportId = UUID.randomUUID();
        ProjectScope scope = new ProjectScope(workspaceId, projectId, pmPrincipal.userId(), Set.of(ProjectRole.PM));
        when(accessPolicy.requireProjectRole(pmPrincipal, workspaceId, projectId, ProjectRole.PM)).thenReturn(scope);

        Report report = new Report();
        report.setId(reportId);
        report.setProjectId(projectId);
        report.setStatus(Report.Status.APPROVED);
        when(reportRepository.findByIdAndProjectId(reportId, projectId)).thenReturn(Optional.of(report));

        ReportResponse response = reportService.publishReport(pmPrincipal, workspaceId, projectId, reportId);

        assertThat(response.status()).isEqualTo("PUBLISHED");
        assertThat(response.visibleToClient()).isTrue();
        assertThat(response.publishedAt()).isNotNull();
    }

    @Test
    @DisplayName("exportReport PDF generates valid bytes starting with %PDF-1.4 and ending with %%EOF")
    void exportPdfValidBytes() {
        UUID reportId = UUID.randomUUID();
        ProjectScope scope = new ProjectScope(workspaceId, projectId, pmPrincipal.userId(), Set.of(ProjectRole.PM));
        when(accessPolicy.requireProjectMember(pmPrincipal, workspaceId, projectId)).thenReturn(scope);

        Report report = new Report();
        report.setId(reportId);
        report.setProjectId(projectId);
        report.setType(Report.Type.WEEKLY);
        report.setPeriodStart(LocalDate.of(2026, 11, 1));
        report.setPeriodEnd(LocalDate.of(2026, 11, 7));
        report.setStatus(Report.Status.PUBLISHED);
        when(reportRepository.findByIdAndProjectId(reportId, projectId)).thenReturn(Optional.of(report));

        when(jdbcTemplate.queryForObject(contains("SELECT name FROM projects"), eq(String.class), eq(projectId)))
                .thenReturn("DevSphere Alpha");

        byte[] pdf = reportService.exportReport(pmPrincipal, workspaceId, projectId, reportId, "PDF");

        assertThat(pdf).isNotEmpty();
        String pdfHeader = new String(pdf, 0, 9, StandardCharsets.US_ASCII);
        assertThat(pdfHeader).isEqualTo("%PDF-1.4\n");

        String pdfEnd = new String(pdf, pdf.length - 6, 6, StandardCharsets.US_ASCII);
        assertThat(pdfEnd).isEqualTo("%%EOF\n");
    }

    @Test
    @DisplayName("exportReport XLSX generates valid zip containing workbook.xml and sheet1.xml")
    void exportXlsxValidZip() throws IOException {
        UUID reportId = UUID.randomUUID();
        ProjectScope scope = new ProjectScope(workspaceId, projectId, pmPrincipal.userId(), Set.of(ProjectRole.PM));
        when(accessPolicy.requireProjectMember(pmPrincipal, workspaceId, projectId)).thenReturn(scope);

        Report report = new Report();
        report.setId(reportId);
        report.setProjectId(projectId);
        report.setType(Report.Type.SPRINT);
        report.setPeriodStart(LocalDate.of(2026, 11, 1));
        report.setPeriodEnd(LocalDate.of(2026, 11, 14));
        report.setStatus(Report.Status.PUBLISHED);
        when(reportRepository.findByIdAndProjectId(reportId, projectId)).thenReturn(Optional.of(report));

        when(jdbcTemplate.queryForObject(contains("SELECT name FROM projects"), eq(String.class), eq(projectId)))
                .thenReturn("DevSphere Alpha");

        byte[] xlsx = reportService.exportReport(pmPrincipal, workspaceId, projectId, reportId, "XLSX");

        assertThat(xlsx).isNotEmpty();

        Set<String> entryNames = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }

        assertThat(entryNames).contains(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/_rels/workbook.xml.rels",
                "xl/workbook.xml",
                "xl/worksheets/sheet1.xml"
        );
    }

    @Test
    @DisplayName("CLIENT role cannot view or export unpublished reports")
    void clientCannotAccessUnpublishedReport() {
        UUID reportId = UUID.randomUUID();
        ProjectScope clientScope = new ProjectScope(workspaceId, projectId, clientPrincipal.userId(), Set.of(ProjectRole.CLIENT));
        when(accessPolicy.requireProjectMember(clientPrincipal, workspaceId, projectId)).thenReturn(clientScope);

        Report report = new Report();
        report.setId(reportId);
        report.setProjectId(projectId);
        report.setStatus(Report.Status.DRAFT);
        report.setVisibleToClient(false);
        when(reportRepository.findByIdAndProjectId(reportId, projectId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> reportService.getReport(clientPrincipal, workspaceId, projectId, reportId))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("not published or visible to client");

        assertThatThrownBy(() -> reportService.exportReport(clientPrincipal, workspaceId, projectId, reportId, "PDF"))
                .isInstanceOf(ReportException.class)
                .hasMessageContaining("not published or visible to client");
    }
}
