# Devsphere

Theo dõi [lộ trình backend](docs/backend/delivery-plan.md) và [nhật ký triển khai](docs/backend/implementation-log.md).

Backend Java 27 / Spring Boot 4.1.2, PostgreSQL 18.7. Database được quản lý bằng Flyway và kiểm tra mapping bằng Hibernate `validate`.

## Khởi động database và backend

Cấp `AUTH_SESSION_SIGNING_KEY` trước khi chạy backend. Khóa gồm 32 byte
ngẫu nhiên mã hóa Base64, được cung cấp qua kho bí mật hoặc biến môi trường.
Backend kiểm tra khóa khi khởi động. Đặt issuer và audience theo hướng dẫn
ở phần xác thực phiên bên dưới.

Copy `.env.example` thành `.env`, đặt hai mật khẩu khác nhau cho tài khoản migration và tài khoản ứng dụng. File `.env` được Git bỏ qua. Dùng các dòng `KEY=value` không bọc dấu nháy trong file local này; Spring Boot đọc nó như Java properties. Với mật khẩu chứa dấu gạch chéo ngược hoặc cú pháp đặc biệt, cấu hình qua biến môi trường thay vì file local.

```sh
cp -n .env.example .env
docker compose up -d --wait database
docker compose run --rm database-setup
./mvnw spring-boot:run
```

Chạy Maven bằng JDK 27. Nếu dùng JDK local đã có trong dự án:

```sh
JAVA_HOME="$PWD/.tools/jdk-27/Contents/Home" ./mvnw spring-boot:run
```

Container `Devphere-DB` bind tại `127.0.0.1:54329`; database mặc định là `devsphere`. Ứng dụng dùng `devsphere_app`, Flyway dùng owner `devsphere`. Dữ liệu được giữ trong volume `devsphere_database-data`. Nếu đổi `DATABASE_PORT`, cập nhật cả `DATABASE_URL`.

SQL bootstrap tạo role runtime khi khởi tạo volume mới. Lệnh `database-setup` có thể chạy lại cho volume hiện có và khi đổi mật khẩu. Sau mỗi lần Flyway migrate, callback Java tự áp dụng quyền runtime, bảo vệ audit/baseline và chặn truy cập lịch sử Flyway. Spring Boot tự nạp `.env` tại thư mục làm việc; biến môi trường có độ ưu tiên cao hơn.

Để migrate và kiểm tra mapping mà không mở HTTP:

```sh
./mvnw -DskipTests spring-boot:run -Dspring-boot.run.arguments=--spring.main.web-application-type=none
```

## Kiểm thử

```sh
./mvnw verify
```

Testcontainers tạo PostgreSQL riêng, kiểm tra SQL bootstrap trên volume mới, Flyway callback, migration, mapping, concurrency, rollback và các ràng buộc nghiệp vụ. Không sử dụng database local để lưu dữ liệu test.

## Tài liệu database

- [Tổng quan và thiết kế](docs/database/README.md)
- [Gallery ảnh ERD](docs/database/index.html)
- [Data dictionary](docs/database/data-dictionary.md)
- [Quyết định thiết kế](docs/database/decisions.md)
- [Ánh xạ yêu cầu và kiểm thử](docs/database/traceability.md)

Ảnh ERD, gallery và data dictionary đã xuất vẫn được giữ trong dự án. Các file Mermaid `.mmd` đi kèm là nguồn sơ đồ có thể chỉnh sửa và render bằng công cụ hỗ trợ Mermaid.

Ứng dụng cung cấp nền tảng persistence và giao dịch. API nghiệp vụ, xác thực HTTP/WebSocket, GitHub/AI worker và solver sẽ sử dụng các module/port này ở các bước triển khai tính năng.

## Backend đăng ký và xác minh email

Các endpoint đăng ký và xác minh email gồm `POST /api/v1/auth/register`, `POST /api/v1/auth/verify-email` và `POST /api/v1/auth/verification/resend`, tuân thủ đúng [hợp đồng đăng ký và OTP](docs/api/email-registration.openapi.yaml). Giao diện frontend hiện vận hành với cơ chế xác thực giả lập (mock auth).

Để bật đăng ký, cấu hình `AUTH_MAIL_FROM`, `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` và thông tin xác thực SMTP theo nhà cung cấp. Dùng TLS theo cấu hình Spring Mail của môi trường. Cấp hai khóa khác nhau cho `AUTH_OTP_HMAC_KEY` và `AUTH_OTP_ENCRYPTION_KEY`. Mỗi khóa là 32 byte ngẫu nhiên mã hóa Base64. Giữ chúng trong kho bí mật hoặc biến môi trường, ngoài Git. Duy trì cùng bộ khóa giữa các instance để các job đang chờ vẫn xử lý được.

Khi cả hai khóa OTP để trống hoặc thiếu dịch vụ mail, endpoint đăng ký và gửi lại trả `503 AUTH_NOT_CONFIGURED`. Cấu hình một khóa OTP hoặc khóa sai định dạng làm backend báo lỗi khi khởi động. OTP hết hạn sau 10 phút. Worker xử lý retry SMTP từ database, nên một mã có thể được gửi lặp nếu tiến trình bị gián đoạn ngay sau khi SMTP nhận thư. Môi trường public cần giới hạn lưu lượng theo IP ở gateway ngoài giới hạn số lần thử và gửi lại theo tài khoản.

Mỗi lần gửi thư kiểm tra mã, tài khoản và lease trước khi gọi SMTP. Nếu mã
được xác minh hoặc thay thế sau lần kiểm tra đó, thư đang gửi có thể vẫn đến.
Mã cũ mất hiệu lực ngay khi giao dịch xác minh hoặc gửi lại hoàn tất.
Lần retry kiểm tra lại trạng thái và dọn dữ liệu OTP của job đã mất hiệu lực.


## Xác thực phiên backend

Đặt `AUTH_SESSION_SIGNING_KEY` vào môi trường của tiến trình backend bằng
secret manager hoặc shell. Khóa phải là 32 byte mã hóa Base64. Backend báo lỗi
khi khởi động nếu thiếu khóa hoặc khóa sai định dạng. Có thể cấu hình
`AUTH_SESSION_ISSUER` và `AUTH_SESSION_AUDIENCE` cho môi trường triển khai.

Client web gọi `GET /api/v1/auth/csrf`, giữ cookie và dùng `headerName` cùng
`token` từ response khi gửi login, refresh và logout. Dùng HTTPS và cùng origin
với API. Truyền access token qua `Authorization: Bearer <token>`; cookie
refresh được trình duyệt quản lý. Khi refresh trả 401, bắt đầu đăng nhập mới.
Client cần phối hợp các yêu cầu refresh để tránh gửi đồng thời cùng một token.

Đăng nhập có giới hạn trong từng tiến trình theo email và địa chỉ kết nối
trực tiếp. Khi chạy sau proxy hoặc nhiều replica, áp dụng thêm giới hạn dùng
chung tại gateway. `GET /api/v1/auth/sessions` liệt kê phiên của tài khoản hiện
tại. `DELETE /api/v1/auth/sessions/{familyId}` thu hồi một họ phiên.

Xem [hợp đồng API phiên](docs/backend/sessions-openapi.yaml) và
[nhật ký triển khai](docs/backend/sessions-implementation-log.md).
