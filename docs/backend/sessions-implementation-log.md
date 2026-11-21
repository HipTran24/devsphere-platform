# Đăng nhập JWT và quản lý phiên

## 2026-11-20

- Nhánh `codex/m1-auth-sessions` phát triển trên bản đăng ký và OTP M1.1.
- Gemini 3.8 Flash qua Antigravity CLI cung cấp mã JWT, luồng đăng nhập/refresh/đăng xuất, cấu hình Spring Security, giới hạn đăng nhập và truy vấn/thu hồi phiên. Sol tích hợp mã với schema, nối API và thêm kiểm thử.
- Migration V008 thêm dấu rotation để phân biệt token đã được thay thế với phiên bị đăng xuất, khóa hoặc thu hồi. Token access có thời hạn 15 phút. Họ phiên refresh hết hạn sau 14 ngày kể từ lúc đăng nhập, kể cả khi có rotation. Dùng lại refresh token đã rotation thu hồi toàn bộ họ phiên.
- Luna QA sơ bộ yêu cầu backend từ chối khởi động khi khóa ký thiếu hoặc sai định dạng. Bản sửa kèm kiểm thử cho cấu hình khóa là một phần của ứng viên M1.2.
- Sol review sơ bộ yêu cầu bằng chứng xuyên qua HTTP, Spring Security và PostgreSQL. Đã thêm hai ca dùng service thật trong `DevsphereApplicationTests`, bao phủ CSRF, refresh, thu hồi khi dùng lại token và phạm vi tài khoản. Bộ tích hợp dùng PostgreSQL 18.7 qua Testcontainers.

## 2026-11-21

- Commit gốc của M1.2 là `1848aa6`, thuộc nhánh đăng ký và OTP `codex/m1-auth-registration` đã có trên GitHub.
- JDK 27 Maven verify đạt 87 test, gồm 62 ca PostgreSQL và 25 ca kiểm tra HTTP cô lập, cấu hình hoặc logic. Không có test bị bỏ qua.
- Ca kết hợp dùng HTTP qua MockMvc, Spring Security và PostgreSQL thật để đăng ký, xác minh, đăng nhập bằng mật khẩu 128 Unicode code point, refresh rồi đăng xuất. Token cũ và phiên đã thu hồi bị từ chối.
- Thời điểm ghi và kiểm tra trạng thái phiên dùng đồng hồ PostgreSQL, đồng bộ với trigger khóa tài khoản. Biên độ dài mật khẩu và email ở API đăng nhập thống nhất với đăng ký.
- Chốt kết hợp đạt 27 test workflow, 12 test frontend, lint, build và 14 test Playwright. Playwright dùng mock authentication. Endpoint bảo vệ bằng Bearer trả challenge WWW-Authenticate khi thiếu hoặc sai token.
- Bổ sung các ca HTTP/Spring Security/PostgreSQL cho sai mật khẩu, tài khoản khóa/chưa xác minh, JWT sai chữ ký/issuer/audience/purpose/hết hạn, phiên database hết hạn và refresh đồng thời. Các JWT giả lập được ký bằng khóa chỉ dùng trong test.
