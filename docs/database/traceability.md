# Ánh xạ nghiệp vụ vào persistence

| Nghiệp vụ | Bảng chính | Bảo vệ và kiểm chứng |
|---|---|---|
| FC-01 đăng nhập và phiên | users, oauth_accounts, auth_tokens, auth_sessions | Email citext, provider/subject unique, token hash, consumption một chiều, revoke khi LOCKED; test email/OTP và session inet |
| WF-02 Workspace Setup | workspaces, workspace_members, workspace_invitations, projects | OWNER deferred, một Project, invitation pending unique; test thiếu OWNER và Project thứ hai |
| WF-03 chuyển OWNER/PM | workspace_members, project_members, project_member_roles, projects | OWNER/PM độc lập, receipt đúng người, nomination có version/hạn dùng; test transfer và pending acceptance |
| Nhiều vai trò | project_members, project_member_roles | Unique active role, một PM, role history; test nhiều role và thu hồi membership |
| FC-02 Requirement/CR | requirements, requirement_versions, plan_proposals | Version hiện hành FK deferred, approved version immutable, draft tách chính thức; test version và proposal draft |
| WF-07 Task | work_items, acceptance_criteria | Hierarchy, trạng thái, blocked, PM acceptance, DONE freeze, khóa công việc tự cấp; test state machine, JPA và key đồng thời |
| Dependency | work_item_dependencies | Cùng Project, không tự phụ thuộc, DAG có Project lock; test cycle tuần tự và đồng thời |
| Scrum | sprints, work_items, milestones | Một Sprint ACTIVE, đóng trả Backlog, snapshot bất biến; milestone cần CLIENT và published; test Sprint/Milestone |
| Resource | developer_capacity | Effective period exclusion, typed skill/calendar, không overlap; test số liệu, khoảng trùng và JSON round trip |
| Planning | plan_proposals, schedule_versions, schedule_entries | Source version, consent, immutable baseline, frozen entry, no-overlap; test stale, deadline, frozen và rollback |
| FC-04 GitHub | github_repositories, github_events, github_activities | Scope composite, delivery unique, hoạt động chưa có Task được phép; test webhook duplicate |
| CI/Review | ci_runs, code_reviews, ai_jobs | SHA, run_attempt, reviewer độc lập, current review view; test SHA cũ và tự approve |
| Monitoring | alerts | Unique open fingerprint, previous episode, audit; test dedup và đợt mới sau resolved |
| FC-06 Communication | chat_channels, chat_channel_members, chat_messages, notifications | Membership, client boundary, cùng channel khi reply, event_key unique; test channel và notification |
| Storage | attachments | Một owner đúng kind, scope, scan khi client-shared, private storage metadata; test ownership và scan |
| FC-07 Report | reports, ai_jobs | PM approval, VALID AI, published/visible view; test generation SUCCESS chưa đủ publish |
| AI routing/validation | workspaces, ai_jobs | Route snapshot, output schema version, generation/validation tách biệt, chặn secret keys trong JSON |
| Audit/Outbox | audit_logs, background_jobs | Append-only, actor context, transaction chung, idempotency fingerprint, expiring lease; test rollback, retry, claim đồng thời |
| Thiết lập database | SQL bootstrap và Java Flyway callback | Khởi tạo role runtime trên volume mới, đăng nhập bằng role riêng, áp dụng lại quyền và bảo vệ audit/baseline sau migrate; không dùng script ngoài stack Java/Docker/SQL |

Các test nằm trong `src/test/java/com/project/devsphere/DevsphereApplicationTests.java`, chạy trên PostgreSQL 18.7 riêng bằng Testcontainers. Đây là bằng chứng tầng database/persistence, không phải E2E của các tính năng API/UI chưa được xây dựng.
