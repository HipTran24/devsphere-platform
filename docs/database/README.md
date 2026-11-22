# Database Devsphere

36 bảng nghiệp vụ, cộng bảng kỹ thuật `flyway_schema_history`. Nguồn nghiệp vụ tạm thời là phần H của đặc tả Feature v1.1 và yêu cầu bổ sung nhiều vai trò của chủ dự án. Schema mới, không chuyển đổi dữ liệu cũ.

## Ranh giới nghiệp vụ

| Nhánh | Bảng | Chức năng |
|---|---:|---|
| Identity và Workspace | 7 | Tài khoản, OAuth, OTP, phiên đăng nhập, Workspace, thành viên, lời mời |
| Project và vai trò | 3 | Project duy nhất, membership và các lần cấp vai trò |
| Requirement và Scrum | 7 | Yêu cầu có phiên bản, Work Item, Dependency, tiêu chí, Sprint và Milestone |
| Capacity và Planning | 4 | Kỹ năng, lịch làm việc, phương án dự thảo và baseline |
| GitHub và Quality | 5 | Inbox webhook, hoạt động GitHub, CI và review |
| Monitoring và Reporting | 2 | Alert, đợt tái diễn, báo cáo và công bố |
| Communication và Storage | 5 | Channel, thành viên, tin nhắn, thông báo và tệp |
| AI và Operations | 3 | Job AI, audit và background job/outbox |

Xem [gallery ERD](index.html), [data dictionary](data-dictionary.md) và [catalog JSON](schema-catalog.json). Mỗi bảng xuất hiện trong đúng một nhánh chính. Quan hệ liên nhánh vẫn tồn tại trong PostgreSQL và được liệt kê trong dictionary.

## Migration và thay đổi về sau

| Migration | Nội dung |
|---|---|
| V001 | Extension citext/btree_gist, Identity và Workspace |
| V002 | Project, nhiều vai trò, Requirement và Scrum |
| V003 | Capacity, Proposal, Schedule Version và Entry |
| V004 | GitHub và Code Quality |
| V005 | Alert, chat, notification, attachment, report, AI, audit và jobs |
| V006 | FK ghép, bất biến dữ liệu, bảo vệ lịch sử, kiểm tra workflow và các view |
| V007 | Job gửi OTP xác minh email và chỉ mục tra cứu mã xác minh theo người dùng |
| V008 | Dấu rotation cho refresh token và ràng buộc lịch sử phiên |
| V009 | Ràng buộc mục đích token và job AUTH_RESET_PASSWORD, grant digest và chỉ mục recovery |

V001–V006 tạo nền tảng lưu trữ. V007 thêm loại job `AUTH_VERIFY_EMAIL` và chỉ mục cho token xác minh theo người dùng. V008 ghi dấu thời điểm rotation của phiên. V009 hỗ trợ khôi phục mật khẩu qua OTP và grant token đơn dụng. Flyway áp dụng đủ chín migration trước khi backend khởi động. Schema vẫn có 36 bảng nghiệp vụ. Bộ ERD, data dictionary và catalog JSON ghi lại mốc V006.

Quy ước: mỗi migration thuộc một commit riêng. Khi migration đã được áp dụng và phát hành, tạo migration mới thay vì sửa checksum cũ. Thay đổi lớn nên theo trình tự thêm cấu trúc tương thích, chuyển dữ liệu theo lô, chuyển ứng dụng rồi mới bỏ cấu trúc cũ. Không dùng Hibernate `update` hoặc `create` để thay đổi schema.

Khóa ngoại tham chiếu vòng được hoàn thiện ở V006. Flyway chạy trước Hibernate validation và startup thất bại nếu migration/mapping không hợp lệ.

## Ghi dữ liệu và concurrency

`ProjectTransactions.execute` là cổng giao dịch cho lệnh PM có ảnh hưởng lớn: khóa đúng Project/Workspace, kiểm tra PM đang hiệu lực, đối chiếu expectedVersion, kiểm tra fingerprint Idempotency-Key, thực hiện callback database, ghi kết quả và sự kiện outbox trong cùng transaction. Callback giao tiếp với các module qua public application port, không thực hiện network IO. Sau commit, worker xử lý sự kiện theo cơ chế ít nhất một lần và consumer phải deduplicate.

Các thay đổi Task, Dependency, Requirement, Sprint, Milestone, Acceptance Criteria, Capacity và membership/role tăng `projects.version`. Đây là chính sách phát hiện stale theo hướng bảo thủ. Sau lệnh có SQL/JPA trộn nhau, reload Project trước khi trả version mới; không giữ một Project entity cũ trong persistence context qua nhiều lệnh.

Mọi thao tác ghi theo Project khóa Project trước, sau đó mới sửa các row liên quan. Giữ transaction ngắn; xử lý retry cho deadlock/serialization failure ở biên command và dùng lại cùng idempotency key. `JobQueue` dùng `SKIP LOCKED`, lease có hạn và kiểm tra worker sở hữu lease khi hoàn tất.

`ds_propose_pm_assignment` và `ds_accept_pm_assignment` giữ PM cũ trong thời gian chờ. Receipt cấp PM lưu người nhận và thời điểm chấp nhận; pending nomination có version, hạn dùng và audit. Không chuyển quyền chỉ bằng thay đổi frontend.

## JSON và lịch sử

Các payload nội bộ lớn có `schemaVersion` hoặc cột version tương ứng. SkillLevel, WeeklySlot và TimeOff có Java value object; PostgreSQL kiểm tra mức kỹ năng, phần trăm, slot/nghỉ trùng và hiệu lực Capacity. Proposal kiểm tra cấu trúc items, tempKey, estimate, target Project và dependency cycle. Payload GitHub giữ hình dạng của nhà cung cấp.

Audit và baseline là append-only. Requirement Version đã duyệt không sửa được. Work Item DONE giữ nguyên dữ liệu nghiệp vụ; tạo CR/Work Item mới cho phạm vi mới. Schedule Entry frozen phải được sao chép nguyên trạng sang baseline sau. Chốt Sprint trả việc chưa DONE về Backlog và giữ snapshot số liệu đã đóng.

JSONB từ chối các khóa secret phổ biến trong object/array lồng nhau. Audit bỏ các trường email, IP, user agent và hash bí mật trực tiếp. Việc lọc nội dung nhạy cảm trong văn bản tự do vẫn phải thực hiện tại application adapter trước khi gọi AI/GitHub hoặc ghi log.

## Phân quyền database và ứng dụng

Role runtime không phải owner, không có quyền DDL/TRUNCATE, không sửa/xóa audit và baseline, không truy cập lịch sử Flyway. Migration owner quản lý schema; production nên cấp credential migration qua quy trình triển khai riêng. SQL bootstrap trong `infra/postgres/runtime-role.sql` tạo role bằng Docker; callback Java `RuntimeRolePrivileges` áp dụng quyền sau mỗi lần Flyway migrate, kể cả khi không có migration mới.

Repository của dữ liệu Project/Workspace yêu cầu scope trong các phương thức đọc. FK ghép chặn liên kết sai tenant. Các ràng buộc này bổ sung cho authorization; HTTP/WebSocket vẫn phải xác minh principal, vai trò và trạng thái membership trước khi gọi application port. Tài khoản database là tài khoản nội bộ đáng tin cậy, không cấp trực tiếp cho người dùng cuối; chưa áp dụng PostgreSQL RLS dựa trên session principal.

Không dùng tài khoản owner của container để chạy ứng dụng thường ngày. Tài khoản owner có thể bảo trì/backup; mọi hành động phá schema hoặc volume cần quy trình vận hành riêng.
