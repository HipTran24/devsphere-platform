# Nhật ký triển khai Milestone 4 — Giám sát dự án, Control Tower, Slack CPM & Tự động Replan

| Thuộc tính | Chi tiết |
|---|---|
| **Hạng mục trong lộ trình** | 22, 23 (Phần H đặc tả DevSphere v1.1) |
| **Nhánh tính năng** | `feature/monitoring-replan` (dựa trên commit `c774a67`) |
| **Hợp đồng API** | [docs/api/monitoring-openapi.yaml](../api/monitoring-openapi.yaml) (OpenAPI 3.1.0) |
| **Mô hình phối hợp** | 5 Subagents chuyên biệt trên `gemini-3.8-flash-high` |
| **Kết quả 6 Gate nghiệm thu** | PASS 100% (608 test backend, 12 test frontend, ESLint 0 warning, Build Next.js sạch, 14 test E2E Playwright, 35 test AI workflow) |

---

## 1. Mục tiêu & Phạm vi triển khai

1. **Bộ phân tích đường găng và độ trễ (CPM Analyzer)**:
   - Thuật toán Kahn sắp xếp topo đồ thị có hướng (DAG), phát hiện chu trình phụ thuộc và ném HTTP 422 `DEPENDENCY_CYCLE_DETECTED`.
   - Forward pass tính Early Start ($ES$), Early Finish ($EF$).
   - Backward pass tính Late Start ($LS$), Late Finish ($LF$).
   - Tính toán Total Slack ($LS - ES$) và Free Slack ($\min(ES_{succ} - EF - lag)$).
   - Trích xuất tập hợp đường găng (Critical Path) và mô phỏng ảnh hưởng hạ nguồn khi một công việc bị trễ (Downstream delay impact analysis).

2. **Công cụ đánh giá tiến độ thực chứng (Progress Evaluator)**:
   - Đánh giá tiến độ dựa trên trọng số tiêu chí nghiệm thu (`acceptance_criteria`).
   - Xử lý biên an toàn: tổng trọng số bằng 0 hoặc không có tiêu chí -> `INSUFFICIENT_EVIDENCE`.
   - Phát hiện chênh lệch (Discrepancy detection) khi tiến độ tự khai báo (`claimed_progress`) vượt tiến độ thực chứng (`verified_progress`) $\ge 25\%$ -> cảnh báo `DISCREPANCY_DETECTED`.
   - Task kỹ thuật (`CODE`): kiểm tra bằng chứng hoàn chỉnh gồm Pull Request, CI Run (`COMPLETED` + `SUCCESS`), Human Code Review (`PASS`) với danh tính GitHub độc lập thông qua `oauth_accounts`.
   - **Bất biến quan sát (Observational Invariant)**: API tính toán tiến độ KHÔNG BAO GIỜ tự ý cập nhật trạng thái công việc sang `DONE` (bảo toàn trigger `ds_work_item_guard`).

3. **Hệ thống cảnh báo thông minh & chống trùng lặp (Alert Subsystem)**:
   - Tạo mã fingerprint SHA-256 xác định chính xác theo định dạng `^[0-9a-f]{64}$`.
   - Chống trùng lặp qua ràng buộc chỉ mục một phần `uq_open_alert_fingerprint` trên `(project_id, fingerprint)` khi `status <> 'RESOLVED'`: cập nhật trực tiếp tại chỗ `evidence`, `impact_snapshot`, và nâng bậc nghiêm trọng theo thứ tự tăng đơn điệu `LOW < MEDIUM < HIGH < CRITICAL`.
   - Tái liên kết cảnh báo (Recurrence Chaining): khi phát hiện lại vấn đề đã giải quyết, tạo bản ghi cảnh báo mới liên kết với bản ghi đã đóng gần nhất qua `previous_alert_id`.
   - Quản lý vòng đời cảnh báo: `NEW -> ACKNOWLEDGED -> IN_PROGRESS -> RESOLVED`, kiểm tra ràng buộc `((status = 'RESOLVED') = (resolved_at IS NOT NULL))`.

4. **Kích hoạt tự động lập kế hoạch lại có kiểm soát (Reschedule Trigger)**:
   - Tạo phương án đề xuất `kind = 'AUTO_REPLAN'`, `status = 'DRAFT'`.
   - Bảo toàn các công việc đã `DONE` và các công việc bị đóng băng trong baseline đang kích hoạt (`ds_schedule_freeze`).
   - Gọi bộ giải Google OR-Tools CP-SAT giải bài toán lập lịch tối ưu.
   - Cơ chế Cập nhật Hai pha (Two-Phase Update) bảo toàn trạng thái `solver_status` của bộ giải (`OPTIMAL` / `FEASIBLE`) không bị trigger `ds_proposal_guard()` thiết lập lại về `NOT_RUN`.
   - **Bất biến bất di bất dịch**: Tuyệt đối KHÔNG tự ý thay đổi baseline kích hoạt của dự án (`active_schedule_version_id`) hoặc hạn cam kết (`committed_deadline`). Mọi thay đổi phải chờ PM và Client phê duyệt.

---

## 2. Kết quả kiểm thử & Nghiệm thu 6 Cổng

```text
[Gate 1] Maven Verify (JDK 27 & PostgreSQL 18.7 Testcontainers):
         Tests run: 608, Failures: 0, Errors: 0, Skipped: 0 - BUILD SUCCESS
[Gate 2] Frontend Unit Tests:
         tests 12, pass 12, fail 0, skipped 0
[Gate 3] Frontend ESLint:
         0 warnings, 0 errors (--max-warnings=0)
[Gate 4] Frontend Next.js Build:
         Compiled successfully in 521ms (11/11 static pages generated)
[Gate 5] Frontend Playwright E2E:
         14 passed (38.3s), 0 failures, 0 skipped
[Gate 6] Python AI Workflow Tests:
         Ran 35 tests in 10.894s - OK (35 passed, 0 failures, 0 skipped)
```
