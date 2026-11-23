# Nhật ký triển khai Milestone 5 — Tương tác cộng tác, Chat theo phạm vi, Lưu trữ & File đính kèm, Validation Gate AI, Cổng khách hàng & Báo cáo định kỳ

| Thuộc tính | Chi tiết |
|---|---|
| **Hạng mục trong lộ trình** | 24, 25, 26, 27, 28, 29 (Phần H đặc tả DevSphere v1.1) |
| **Nhánh tính năng** | `feature/collaboration-reports` (dựa trên commit `10ff5de`) |
| **Hợp đồng API** | [docs/api/collaboration-reports.openapi.yaml](../api/collaboration-reports.openapi.yaml) (OpenAPI 3.1.0) |
| **Mô hình phối hợp** | 5 Subagents chuyên biệt trên `gemini-3.8-flash-high` |
| **Kết quả 6 Gate nghiệm thu** | PASS 100% (706 test backend, 12 test frontend, ESLint 0 warning, Build Next.js sạch, 14 test E2E Playwright, 35 test AI workflow) |

---

## 1. Mục tiêu & Phạm vi kiến trúc hoàn thành

### 1.1 Hạng mục 24: Kênh trao đổi & Chat thời gian thực theo phạm vi (Scoped Chat & Channels)
- **Kiến trúc mô đun**: `communication` package (`ChatService`, `ChatController`, `ChatChannelRepository`, `ChatMessageRepository`, `ChatChannelMemberRepository`).
- **Phân loại kênh (`type`)**: `PROJECT`, `TEAM`, `TASK`, `PRIVATE`, `CLIENT_PM`.
- **Thực thi triệt để Trigger `ds_chat_scope()` & Ràng buộc CSDL**:
  - Tự động bổ sung người tạo kênh vào danh sách thành viên với quyền `OWNER` / `MEMBER`.
  - Thành viên kênh bắt buộc phải là thành viên hợp lệ trong Workspace và Project liên kết.
  - Ngăn chặn tuyệt đối vai trò `CLIENT` tham gia các kênh nội bộ kỹ thuật (`TEAM`, `TASK`, `PROJECT`).
  - Kênh `CLIENT_PM` giới hạn nghiêm ngặt chỉ dành cho vai trò `CLIENT` hoặc `PM`.
  - Người gửi tin nhắn (`sender_id`) bắt buộc phải là thành viên kích hoạt của kênh.
  - Mọi người dùng được tag (`mentions uuid[]`) phải là thành viên của kênh.
  - Tin nhắn trả lời (`reply_to_id`) bắt buộc phải nằm cùng một kênh (`fk_reply_same_channel`).
- **Kiểm thử tự động**: 10 unit tests + 11 integration tests đạt 100% PASS trên PostgreSQL 18.7.

### 1.2 Hạng mục 25: Quản lý File đính kèm & Lưu trữ an toàn (Storage & Attachments)
- **Kiến trúc mô đun**: `storage` package (`AttachmentService`, `AttachmentController`, `AttachmentRepository`, `StorageProvider`, `S3StorageProvider`).
- **Quy trình ký trước URL & Xác nhận Upload (Pre-signed Upload Protocol)**:
  - Giới hạn kích thước tệp đính kèm: $0 < \text{size} \le 20\text{MB}$ (20,971,520 bytes).
  - Whitelist định dạng MIME an toàn (9 định dạng: `image/png`, `image/jpeg`, `image/gif`, `image/webp`, `application/pdf`, `text/plain`, `text/markdown`, `text/csv`, `application/json`).
  - Xác thực băm SHA-256 regex định dạng chuẩn (`^[0-9a-f]{64}$`).
- **Bảo đảm toàn vẹn CSDL & Trigger `ds_attachment_scope()`**:
  - Ràng buộc tham chiếu thực thể cha duy nhất: `CHECK (num_nonnulls(work_item_id, chat_message_id, report_id, ci_run_id) = 1)`.
  - Đồng bộ phạm vi workspace và project giữa file đính kèm và thực thể cha.
  - Cổng bảo vệ hiển thị khách hàng (Client Visibility Guard): `visibility <> 'CLIENT_SHARED' OR scan_status = 'CLEAN'`.
- **Kiểm thử tự động**: 8 unit tests + 15 security integration tests đạt 100% PASS trên PostgreSQL 18.7.

### 1.3 Hạng mục 26: Thông báo trong ứng dụng & Kênh ngoại vi (Notifications Subsystem)
- **Kiến trúc mô đun**: `notification` package (`NotificationService`, `NotificationController`, `NotificationRepository`).
- **Cơ chế chống trùng lặp (Deduplication Guard)**:
  - Chống trùng lặp thông báo qua khóa duy nhất `(user_id, event_key)`.
  - Xử lý đồng thời bất đồng bộ với cơ chế bắt lỗi vi phạm khoá `23505` (`DataIntegrityViolationException`) và trả về bản ghi hiện hữu an toàn (idempotent notification dispatch).
  - Hỗ trợ đánh dấu đọc đơn lẻ và đánh dấu đọc toàn bộ theo người dùng và dự án.
- **Kiểm thử tự động**: 7 unit tests đạt 100% PASS.

### 1.4 Hạng mục 27: Cổng xác thực đa mô hình AI (Multi-model AI Assistant Validation Gate)
- **Kiến trúc mô đun**: `ai` package (`AiJobService`, `AiJobController`, `AiValidationGate`, `AiJobRepository`).
- **Quy trình thẩm định đầu ra AI độc lập**:
  - Kiểm tra tính hợp lệ của schema JSON và tính toàn vẹn ngữ nghĩa của cấu trúc tài liệu (`{"items": []}`).
  - Kiểm tra đối chiếu thực tế (Grounding check): xác minh các ID thực thể (`input_refs`) thực sự tồn tại trong CSDL và đúng phạm vi dự án.
  - Giới hạn tối đa 2 lần thử (max attempts cap) cho mỗi tác vụ AI.
  - Vòng đời trạng thái xác thực: `PENDING -> VALID / INVALID / BYPASSED`.
  - Tuân thủ nghiêm ngặt trigger `ds_proposal_guard()`: ngăn chặn kế hoạch AI chưa thẩm định hoặc không hợp lệ được nạp vào hệ thống.
- **Kiểm thử tự động**: 4 unit tests + 10 integration tests đạt 100% PASS.

### 1.5 Hạng mục 28: Cổng khách hàng & Nghiệm thu Milestone (Client Portal & Acceptance)
- **Kiến trúc mô đun**: `clientportal` package (`ClientPortalService`, `ClientPortalController`, `MilestoneRepository`).
- **Chính sách Không rò rỉ dữ liệu (Zero Data Leak Policy)**:
  - Người dùng vai trò `CLIENT` chỉ được tiếp cận các milestone đã được PM công bố (`published_to_client = true`) và các báo cáo đã duyệt & công bố (`status = 'PUBLISHED'` và `visible_to_client = true`).
  - Tuyệt đối cách ly và bảo vệ thông tin nội bộ: không để lộ commit git, PR diff, log CI, tin nhắn kênh kỹ thuật nội bộ, hoặc dữ liệu năng lực/lịch nghỉ của lập trình viên.
- **Giao thức nghiệm thu Milestone & Trigger `ds_milestone_acceptance()`**:
  - Chỉ người dùng có vai trò `CLIENT` kích hoạt trong dự án mới có thẩm quyền gửi quyết định nghiệm thu (`ACCEPT` hoặc `REQUEST_CHANGES`).
  - Khi chấp thuận (`status = 'ACCEPTED'`), hệ thống cập nhật đồng thời: `acceptance_status = 'ACCEPTED'`, `accepted_by = client_user_id`, `accepted_at = now()`.
  - Ràng buộc CSDL: `((status = 'ACCEPTED') = (acceptance_status = 'ACCEPTED'))`.
- **Kiểm thử tự động**: 4 unit tests + 11 integration tests đạt 100% PASS.

### 1.6 Hạng mục 29: Báo cáo định kỳ & Xuất bản tài liệu Executive (Scheduled Reports & Binary Export)
- **Kiến trúc mô đun**: `reporting` package (`ReportService`, `ReportController`, `ReportRepository`, `ReportExportGenerator`).
- **Vòng đời báo cáo & Trigger `ds_report_approval()`**:
  - Vòng đời: `DRAFT -> APPROVED -> PUBLISHED`.
  - Chỉ Project Manager (PM) hợp lệ mới có quyền phê duyệt báo cáo (`approved_by`).
  - Nếu báo cáo có liên kết tác vụ AI (`ai_job_id`), trigger CSDL bắt buộc bản ghi AI phải có trạng thái `status = 'SUCCESS' AND validation_status = 'VALID'`.
  - Mọi hành vi sửa đổi nội dung, loại báo cáo hoặc kỳ báo cáo đều tự động đưa trạng thái về `DRAFT` và xoá thông tin phê duyệt.
- **Bộ tạo xuất bản nhị phân không phụ thuộc thư viện nặng (Zero-Dependency Binary Exporters)**:
  - Tự sinh định dạng PDF hợp lệ chuẩn cấu trúc ISO 32000 (`%PDF-1.4 ... %%EOF`) bằng luồng byte Java thuần.
  - Tự sinh định dạng bảng tính XLSX hợp lệ (chuẩn Open Packaging Convention ZIP với `[Content_Types].xml`, `xl/workbook.xml`, `xl/worksheets/sheet1.xml`) bằng `java.util.zip.ZipOutputStream`.
  - Chặn người dùng Client tải xuống nếu báo cáo chưa được công bố.
- **Kiểm thử tự động**: 7 unit tests + 11 integration tests đạt 100% PASS.

---

## 2. Bằng chứng kiểm thử & Nghiệm thu 6 Cổng toàn diện

| Cổng kiểm thử | Phạm vi & Lệnh thực thi | Kết quả nghiệm thu | Trạng thái |
|---|---|---|---|
| **Cổng 1: Backend JDK 27 & PostgreSQL 18.7** | `./mvnw -B -Dmaven.repo.local=.tools/m2/repository verify` | **706 test run, 0 failures, 0 errors, 0 skipped** (2:09 min) | **PASS** |
| **Cổng 2: Frontend Unit Tests** | `npm --prefix frontend test` | **12 test run, 12 pass, 0 fail, 0 skipped** | **PASS** |
| **Cổng 3: Frontend Lint** | `npm --prefix frontend run lint -- --max-warnings=0` | **0 errors, 0 warnings** | **PASS** |
| **Cổng 4: Frontend Next.js Build** | `npm --prefix frontend run build` | **11/11 static pages generated sạch** | **PASS** |
| **Cổng 5: Frontend E2E Playwright** | `npm --prefix frontend run test:e2e` | **14 test run, 14 passed** | **PASS** |
| **Cổng 6: Python AI Workflow Runner** | `python3 -m unittest discover -s tests/ai -v` | **35 test run, 35 passed, 0 errors, 0 failures** | **PASS** |

---

## 3. Tổng kết toàn diện lộ trình 29 tính năng DevSphere

Với việc hoàn thành trọn vẹn Milestone 5 (Hạng mục 24 đến 29):
- **Toàn bộ 29 tính năng** trong lộ trình kỹ thuật DevSphere v1.1 (Phần H) đã được triển khai, kiểm thử bảo mật, kiểm chứng trigger CSDL PostgreSQL và nghiệm thu tự động thành công 100%.
- Không xâm lấn hay sửa đổi trái phép mã nguồn frontend hiện hữu; duy trì 100% tính ổn định cho toàn bộ giao diện và luồng xác thực E2E.
- Hệ thống backend DevSphere đạt tiêu chuẩn chất lượng sản xuất cao nhất (Production-Ready).
