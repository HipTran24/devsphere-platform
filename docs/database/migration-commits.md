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
| V007 | `feat(auth): register accounts with email verification` | V007 bổ sung AUTH_VERIFY_EMAIL, chỉ mục token xác thực và quy trình kiểm thử worker trên Testcontainers. |
| V008 | `feat(auth): issue and rotate revocable sessions` | Dấu rotation, bất biến trạng thái phiên và kiểm thử nâng cấp |
| V009 | `feat(auth): queue password recovery with single-use reset grant` | Ràng buộc mục đích token và job, chỉ mục recovery và unique digest của grant |

Phần dọn công cụ hỗ trợ sử dụng Java/Flyway callback và SQL bootstrap của Docker. Công cụ nhập lịch sử tạm thời đã được gỡ khỏi mã nguồn.

Lịch sử đóng gói trong `delivery/` thuộc đợt bàn giao nền tảng V001–V006. Đợt triển khai tính năng dùng các nhánh và điều kiện kiểm thử trong [lộ trình backend](../backend/delivery-plan.md).
