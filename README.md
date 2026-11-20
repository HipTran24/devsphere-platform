# Devsphere

Backend Java 27 / Spring Boot 4.1.2, PostgreSQL 18.7. Database được quản lý bằng Flyway và kiểm tra mapping bằng Hibernate `validate`.

## Khởi động database và backend

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
