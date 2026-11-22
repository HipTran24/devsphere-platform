# Nhật ký triển khai backend DevSphere - Kiểm định chất lượng code & Quality Gate

## Kiểm tra CI Runs, Đánh giá Code Độc lập và Nghiệm thu Task theo Quality Gate (Hạng mục 19, 20, 21)

| Mục | Trạng thái |
| --- | --- |
| Hạng mục trong backlog | 19, 20, 21 |
| Nhánh | `feature/code-quality` |
| Hợp đồng | [OpenAPI](../api/code-quality.openapi.yaml) |
| Tiêu chí | CI runs gắn HEAD SHA của Pull Request; Review độc lập tách biệt tác giả; Quality Gate đánh giá chứng cứ toàn diện (CI completed/success, Human review pass trên cùng version context của project, Acceptance criteria verified); PM nghiệm thu task (CODE task bắt buộc Quality Gate PASS; NON_CODE task dựa trên criteria). |
| Hạ tầng kiểm thử | PostgreSQL 18.7 qua Testcontainers trên JDK 27; Playwright Chromium trên Next.js; Node test suite và ESLint. |

### 1. Kiến trúc và Thành phần Triển khai
- **Lớp Domain & Persistence**:
  - `CiRun.java`: Ánh xạ bảng `ci_runs`, lưu kết quả CI run gắn `repository_id`, `commit_sha`, `status` (`QUEUED`, `IN_PROGRESS`, `COMPLETED`), `conclusion` (`SUCCESS`, `FAILURE`, `CANCELLED`, `SKIPPED`, `TIMED_OUT`, `ACTION_REQUIRED`), metrics (`test_count`, `pass_count`, `fail_count`, `coverage_pct`), `summary_report`.
  - `CodeReview.java`: Ánh xạ bảng `code_reviews`, lưu kết quả review gắn `repository_id`, `pr_number`, `head_sha`, `context_version`, `reviewer_kind` (`HUMAN`, `AI`), `reviewer_id`, `status` (`PASS`, `FAIL`, `REQUIRES_CHANGES`, `DISMISSED`), `findings_summary`.
  - `CiRunRepository.java` & `CodeReviewRepository.java`: Spring Data JPA Repositories cung cấp các query truy vấn theo repository, commit SHA, PR number, và reviewer.
- **Lớp Application & Nghiệp vụ**:
  - `CiRunService.java`: Ghi nhận và truy vấn CI run gắn với repository và commit SHA. Kiểm tra ràng buộc hợp lệ, xác thực quyền truy cập project member.
  - `CodeReviewService.java`: Gửi và truy vấn code review. Tuân thủ trigger `ds_review_evidence()` của database:
    - Bắt buộc reviewer giữ role `DEVELOPER` hiệu lực.
    - Nghiêm cấm tác giả PR tự review (`reviewer_id != pr_author_id`).
    - Bắt buộc reviewer đã liên kết tài khoản GitHub qua `oauth_accounts`.
    - Gắn `context_version` bằng `projects.version` hiện tại để đảm bảo tính thời điểm (không chấp nhận review cũ trên phiên bản context đã thay đổi).
  - `QualityGateService.java`: Đánh giá Quality Gate cho work item:
    - Với `NON_CODE` tasks: Quality Gate PASS khi tất cả tiêu chí nghiệm thu (`acceptance_criteria`) đạt trạng thái `VERIFIED`.
    - Với `CODE` tasks: Quality Gate PASS khi và chỉ khi:
      1. Có PR activity liên kết trong `github_activities`.
      2. CI run mới nhất trên PR HEAD SHA đạt `COMPLETED` và `SUCCESS`.
      3. Code review mới nhất từ người đánh giá độc lập đạt `PASS`, reviewer != PR author, và `context_version == project.version`.
      4. Tất cả `acceptance_criteria` của task đạt `VERIFIED`.
  - `WorkItemService.java`: Tích hợp `qualityGateService.evaluateQualityGate` vào quy trình PM nghiệm thu task `acceptWorkItem`:
    - Nếu task là `CODE` và Quality Gate không đạt `PASSED`, lệnh nghiệm thu bị từ chối với lỗi HTTP 422 `QUALITY_GATE_EVIDENCE_MISSING`.
    - `CreateWorkItemRequest` hỗ trợ chỉ định `workKind` (`CODE` hoặc `NON_CODE`), mặc định là `NON_CODE` cho các tác vụ quản lý dự án thông thường.
- **Lớp REST Controller & OpenAPI**:
  - `CiRunController.java`: Endpoints `/api/v1/workspaces/{w}/projects/{p}/ci-runs`.
  - `CodeReviewController.java`: Endpoints `/api/v1/workspaces/{w}/projects/{p}/code-reviews`.
  - `QualityGateController.java`: Endpoints `/api/v1/workspaces/{w}/projects/{p}/work-items/{id}/quality-gate`.
  - `docs/api/code-quality.openapi.yaml`: Đặc tả OpenAPI 3.1 đầy đủ, chuẩn ProblemDetail RFC 7807 / 9457.

### 2. Kiểm thử và Xác minh Chất lượng
- `CiRunServiceTest`: 6/6 tests PASS.
- `CodeReviewServiceTest`: 6/6 tests PASS.
- `CiRunAndCodeReviewIntegrationTest`: 11/11 tests PASS trên PostgreSQL thực tế.
- `QualityGateAndAcceptanceIntegrationTest`: 8/8 tests PASS trên PostgreSQL thực tế, chứng minh tính toàn vẹn của Quality Gate cho cả CODE và NON_CODE task.
- `SprintProgressAndClosureIntegrationTest` & `WorkItemAndDependencyIntegrationTest`: Toàn bộ 15 tests PASS, đảm bảo không hồi quy tính năng Agile/Scrum.
- **Toàn bộ Regression Pipeline**:
  - Backend `./mvnw clean verify`: **415/415 tests PASS, 0 failures, 0 errors, 0 skipped** (BUILD SUCCESS).
  - Frontend `npm test`: **12/12 tests PASS, 0 errors**.
  - Frontend `npm run lint`: **0 warnings, 0 errors**.
  - Frontend `npm run build`: **Next.js production build thành công hoàn toàn**.
  - Frontend `npm run test:e2e`: **14/14 Playwright E2E tests PASS**.
  - OpenAPI 3.1 Spec: Đã xác thực cú pháp YAML và schema hợp lệ qua js-yaml.
