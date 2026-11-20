# Quyết định thiết kế database

## Phạm vi và các giả định nghiệp vụ

Chủ dự án xác nhận dùng phần H làm chuẩn tạm thời, database thiết kế mới và cần nhiều vai trò tổng quát trong cùng Project. Các chính sách bên dưới là lựa chọn triển khai có ghi nhận cho những câu hỏi chưa có câu trả lời riêng; không coi chúng là phê duyệt nghiệp vụ mới của chủ dự án.

| Quyết định | Cách triển khai và lý do |
|---|---|
| 36 bảng thay vì 35 | Thêm `project_member_roles`. Membership duy nhất theo Project/User; mỗi lần cấp vai trò có row lịch sử riêng, thu hồi một vai trò không làm mất vai trò khác. |
| Vai trò tổng hợp | PM/Developer/Client/Viewer có thể cùng tồn tại. CLIENT-only không vào channel nội bộ; người có thêm vai trò nội bộ nhận quyền của vai trò đó. OWNER vẫn thuộc Workspace và không tự trở thành PM. |
| PM bị LOCKED | Chuyển Project ACTIVE sang ON_HOLD, revoke phiên đăng nhập; chuyển giao PM mới vẫn cần người nhận chấp nhận. Không tự kích hoạt lại Project khi unlock. |
| Đại diện CLIENT | `projects.deadline_approver_id` chỉ định một CLIENT đang hiệu lực. Thiếu đại diện hoặc thiếu xác nhận thì chặn thay đổi committed deadline. |
| Xác nhận deadline | Phải khác người PM phê duyệt Proposal; gắn deadline chính xác, version Proposal và base Project version. Chỉnh nội dung/kỳ hạn làm mất consent trước đó. |
| PM kiêm Developer | Có thể được giao việc khi có DEVELOPER; nghiệm thu Task yêu cầu PM. Hiện không thêm quy tắc người nghiệm thu Task phải khác assignee, vì mỗi Project chỉ có một PM. Human PR review bắt buộc độc lập tác giả. |
| Chat membership | Dùng UUID surrogate cho row membership, cùng unique(channel_id,user_id), hỗ trợ audit và thêm metadata mà không thay định danh row. |
| Kỹ năng | Chuẩn hóa thành mảng `{skill,level}` với level nguyên 1..5. Java chuẩn hóa skill về chữ thường; database chặn kỹ năng trùng không phân biệt hoa thường. |
| Trường mơ hồ | Thay `developer_capacity.developer_capacity` bằng `skills`; biểu diễn nhu cầu Task bằng `required_skills`, đồng thời có `labels` riêng. |
| Các trường còn thiếu | Bổ sung objective, backlog_rank, time_spent_minutes, work_kind, acceptance actor, review context, CI run_attempt và nguồn ai_job. Đây là dữ liệu cần thiết cho các chức năng đã có trong đặc tả. |

## Ràng buộc và lịch sử

ID, Workspace và Project gắn với row là bất biến. Các quan hệ nhạy cảm dùng FK ghép, kể cả Requirement Version, Schedule Entry, GitHub, chat và attachment. Membership đang ACTIVE phải thuộc Workspace membership ACTIVE; tài khoản LOCKED không có quyền hiệu lực qua `ds_has_project_role`.

Unique index giới hạn tối đa một OWNER/PM, constraint trigger deferred kiểm tra sự tồn tại hợp lệ ở cuối transaction. Vì thế chuyển giao có thể sửa nhiều row nhưng không commit trạng thái thiếu OWNER hoặc thiếu PM cho Project ACTIVE.

Giữ UUID trên quan hệ liên module thay cho import JPA entity nội bộ của module khác. FK ở PostgreSQL vẫn giữ toàn vẹn dữ liệu. Các bảng không cùng vòng đời không bị ép vào một BaseEntity chứa đầy đủ timestamp/version/soft-delete; `UuidEntity` chỉ cung cấp ID.

Các bảng cốt lõi không hard-delete hàng loạt. Các FK mặc định ngăn xóa đối tượng còn được tham chiếu. Audit là lịch sử nghiệp vụ; snapshot mặc định không ghi hash đăng nhập. Purge PII, retention job/webhook và partition audit khi có tải lớn cần migration/quy trình vận hành được kiểm thử riêng, không bật partition hoặc xóa lịch sử tự động khi chưa có dữ liệu tải thực.

## Phiên bản và kết quả bên ngoài

`projects.version` là revision nguồn dùng chung và có thể tăng nhiều lần trong một command. Không giả định version tăng đúng một cho một request. Baseline `version_no`, Requirement `version_no`, Proposal optimistic version, JSON schema version và Project source version có ý nghĩa khác nhau.

Review gắn SHA và context version; view `current_pr_reviews` loại bỏ PASS của SHA/context cũ. Context hiện dùng revision Project, nên thay đổi nguồn khác cũng có thể làm review stale; có thể tách quality-context revision nhỏ hơn bằng migration tương lai nếu cần giảm invalidation mà vẫn bảo toàn chứng cứ.

AI job SUCCESS chỉ thể hiện đã sinh xong. Proposal/AI review/báo cáo được phê duyệt cần kết quả VALID. Mapping không tự chạy AI, solver hoặc GitHub và không cho PR merge tự chuyển Task thành DONE.

## Các điểm mở rộng có chủ đích

Nếu sau này cần nhiều CLIENT đồng phê duyệt, nhiều assignee trên Task, repository dùng chung giữa nhiều Project hoặc timesheet theo từng lần ghi giờ, bổ sung bảng quan hệ/nhật ký tương ứng bằng migration mới. Hiện time_spent_minutes lưu tổng, audit giữ các lần thay đổi; đó chưa phải bảng timesheet để tính công/lương.

Các chính sách mạng cho self-host AI, SSRF, kiểm tra quyền GitHub tại nhà cung cấp, bằng chứng DoD đầy đủ và phân quyền ở HTTP/WebSocket thuộc application adapter/use case. Database lưu đủ định danh, phiên bản và actor để các lớp đó kiểm tra; không coi FK hay caller-supplied actor ID là xác thực người gọi.
