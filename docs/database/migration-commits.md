# Commit theo migration

Mỗi file SQL migration thuộc một commit riêng, cùng mapping liên quan. Các thay đổi vận hành sau đó được tách thành commit bảo trì, không sửa nội dung migration đã áp dụng.

| Migration | Commit message | Mapping đi kèm |
|---|---|---|
| V001 | `feat(db): add identity and workspace persistence` | Identity, Workspace, shared persistence, Maven/Flyway/Docker |
| V002 | `feat(db): add project roles and scrum persistence` | Project, nhiều role, Requirement, Work Item, Sprint và skill contract |
| V003 | `feat(db): add capacity and versioned planning persistence` | Resource, Planning, Scheduling |
| V004 | `feat(db): add github and code quality persistence` | Git integration và Code Quality |
| V005 | `feat(db): add collaboration ai and operations persistence` | Monitoring, Communication, Notification, Storage, Reporting, AI, Audit, Operations |
| V006 | `feat(db): enforce tenant integrity and workflow invariants` | FK/trigger/view, transaction, outbox, kiểm thử và tài liệu ERD |

Phần dọn công cụ hỗ trợ sử dụng Java/Flyway callback và SQL bootstrap của Docker. Công cụ nhập lịch sử tạm thời đã được gỡ khỏi mã nguồn.

Lịch sử đóng gói trong `delivery/` là tài liệu bàn giao, không được ứng dụng sử dụng khi chạy. Chỉ dùng gói có manifest khớp snapshot hiện tại. Checkout gốc chỉ có thể cập nhật Git metadata khi permission profile cho phép ghi `.git`. Mục tiêu bàn giao là commit trực tiếp trên `main` và không push remote.
