# Lộ trình hoàn thiện backend DevSphere

Các mục bên dưới giữ nguyên số thứ tự trong danh sách 29 hạng mục. Nhánh tính năng có tiền tố `codex/`. Khi phụ thuộc chưa có trên main, nhánh tiếp theo dựa trên commit đã được xác minh của nhánh trước. GitHub nhận từng nhánh sau khi đạt kiểm thử và review.

| Nhánh | Hạng mục | Điều kiện nghiệm thu |
| --- | --- | --- |
| `codex/m1-auth-registration` | 1, 2 | Đăng ký với Argon2id, email duy nhất, OTP một lần giới hạn thử/gửi, gửi SMTP có retry và kiểm chứng trên PostgreSQL. |
| `codex/m1-auth-sessions` | 3 | JWT ngắn hạn, refresh cookie có CSRF guard, rotation nguyên tử, logout/revoke, khóa tài khoản chặn phiên. |
| `codex/m1-password-recovery` | 4 | Phản hồi nhất quán cho email, OTP đúng purpose, grant một lần có hạn dùng, đổi mật khẩu và hủy phiên đồng thời. |
| `codex/m1-access-admin` | 5, 6 | Guard hệ thống/Workspace/Project trên các API, nguyên tắc từ chối mặc định, Admin khóa/mở và audit đúng actor; kiểm thử chéo tenant. |
| `codex/m1-workspace-project` | 7 | Workspace + Owner nguyên tử, một Project/Workspace, danh sách đúng scope; chỉ PM hiệu lực được kích hoạt sau khi đủ điều kiện. |
| `codex/m1-membership-transfers` | 8 | Invitation dùng một lần, revoke cắt quyền, nhiều role, chuyển Owner độc lập PM, PM cũ giữ quyền đến khi người nhận chấp nhận. |
| `codex/m1-requirement-backlog` | 9, 10 | Requirement version bất biến khi duyệt, CR ghi bản nháp và quyết định, Task có phân cấp đúng, dependency không vòng, tiêu chí và chuyển trạng thái đúng quyền. |
| `codex/m1-sprint-progress` | 11 | Kế hoạch và vòng đời Sprint, đóng Sprint lưu snapshot, trả việc chưa xong, số liệu dựa trên dữ liệu đã nghiệm thu. |
| `codex/m2-github-connection` | 16, 17, 18 | Installation scope chính xác, webhook xác thực/chống trùng tại ingress, retry và ánh xạ branch/commit/PR vào đúng Project và Task. Kiểm tra môi trường GitHub App riêng. |
| `codex/m2-code-quality` | 19, 20, 21 | CI và human review gắn HEAD SHA, review độc lập tác giả, gate chặn khi thiếu bằng chứng. Merge chỉ cung cấp bằng chứng, PM nghiệm thu Task. Đã hoàn thành và xác minh: [code-quality-implementation-log.md](code-quality-implementation-log.md). |
| `codex/m3-developer-capacity` | 12 | Quản lý kỹ năng, ca làm, nghỉ và phạm vi hiệu lực. Kiểm tra overlap và đảm bảo đúng Project. Đã hoàn thành và xác minh: [developer-capacity-implementation-log.md](developer-capacity-implementation-log.md). |
| `codex/m3-solver-proposals` | 13, 14 | OR-Tools chạy giới hạn thời gian, model tuân thủ constraint, phân biệt FEASIBLE/OPTIMAL/INFEASIBLE/UNKNOWN, lưu bản nguồn và diagnostics. Đã hoàn thành và xác minh: [solver-proposals-implementation-log.md](solver-proposals-implementation-log.md). |
| `codex/m3-plan-approval` | 15 và Apply của 9 | Preview trước/sau, PM duyệt, CLIENT xác nhận deadline cam kết, commit nguyên tử hoặc rollback, idempotency, stale và lịch baseline versioned. |
| `codex/m4-monitoring-replan` | 22, 23 | CPM có slack và ảnh hưởng hạ nguồn, alert chống trùng/reopen, tiến độ theo chứng cứ, replan giữ frozen và đợi PM. |
| `codex/m5-chat-notifications` | 24, 26 | REST/WebSocket theo quyền và membership hiện tại, lịch sử có phân trang, outbox phân phối có idempotency, retry email có giới hạn. |
| `codex/m5-private-attachments` | 25 | S3 private, xác nhận upload, loại/kích thước/scan, URL hết hạn và chặn tải vượt quyền. |
| `codex/m5-project-ai` | 27 | Route theo use case và riêng tư, context đúng quyền, validation schema/ID/source/evidence, job retry/timeout, chặn nội dung sai và tắt được AI. |
| `codex/m5-client-portal` | 28 | Dữ liệu công bố, chỉ Client hợp lệ nghiệm thu Milestone, quyết định có lịch sử, dữ liệu nội bộ bị chặn. |
| `codex/m5-scheduled-reports` | 29 | Facts từ nguồn phiên bản, duyệt trước công bố, export PDF/XLSX và kiểm quyền tải, lịch chạy có idempotency. |

Flash triển khai theo hợp đồng API và tiêu chí đã ghi. Sol chạy kiểm tra thực tế và kiểm tra thay đổi. Luna đánh giá độc lập chất lượng, bảo mật, nghiệp vụ và chứng cứ. Sol review cuối, xác nhận các phát hiện và commit có mô tả hành vi. Mỗi đợt cập nhật nhật ký kỹ thuật và README trong chính nhánh giao hàng. Không ghi secrets, dữ liệu máy, log thô, cache, kết quả E2E hoặc output build vào Git.

Đặc tả v1.1 phần H ưu tiên khi mâu thuẫn. Các quyết định persistence hiện hữu được đối chiếu tại `docs/database/decisions.md`. Tích hợp bên ngoài chỉ ghi nhận đã xác minh khi có lời gọi thật hoặc kiểm thử adapter với môi trường được kiểm soát và nêu rõ phạm vi của bằng chứng.
