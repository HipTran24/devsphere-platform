# Nhật ký triển khai backend DevSphere

## Đăng ký tài khoản và OTP

| Mục | Trạng thái |
| --- | --- |
| Hạng mục trong backlog | 1–2 |
| Nhánh | `codex/m1-auth-registration` |
| Hợp đồng | [OpenAPI](../api/email-registration.openapi.yaml) |
| Tiêu chí | Tạo account PENDING, xác minh một lần, gửi lại có giới hạn, gửi mail có retry, API và PostgreSQL được kiểm thử. |
| Kết nối GitHub | Đã kiểm tra dry-run quyền push. Nhánh tính năng được controller đẩy sau khi kiểm thử và review cùng snapshot đều đạt. |
| Hạ tầng kiểm thử | Docker Desktop chạy PostgreSQL 18.7 qua Testcontainers. Playwright dùng Chromium trong image Linux chính thức trùng phiên bản lockfile và chạy cùng lệnh E2E. |

Flash đã cung cấp mã nguồn cho mã hóa OTP, service đăng ký, lớp HTTP và worker email theo từng lượt nhỏ. Sol áp dụng bản nháp trong checkout riêng và chỉnh các điểm tích hợp với schema và hợp đồng API. Migration V007 thêm loại job xác minh, giữ nguyên 36 bảng.

Báo cáo kiểm thử ghi nhận toàn bộ 61 kiểm thử backend trên JDK 27 (trong đó 52 kiểm thử sử dụng PostgreSQL), 27 kiểm thử workflow, 12 kiểm thử frontend unit kèm lint/build, cùng 14 kiểm thử Playwright E2E đều đạt chuẩn, không có bài nào bị bỏ qua. Bộ kiểm thử Playwright dùng xác thực mô phỏng. Các ca backend mô phỏng SMTP có kiểm soát.

Luna đánh giá thêm các tiêu chí nghiệm thu sau khi Docker và Playwright hoạt động. Flash bổ sung các ca cho mã hết hạn, tài khoản bị khóa, OTP sai mục đích, gửi lại đồng thời và worker SMTP xử lý job hết hạn hoặc hết lượt thử. Runner ghi kết quả riêng cho mỗi snapshot. Phát hành yêu cầu kiểm thử đầy đủ, Luna QA và Sol review trên cùng snapshot.

Xem [lộ trình giao hàng](delivery-plan.md) để đối chiếu đủ 29 hạng mục và các nhánh phụ thuộc.

Sol review yêu cầu dùng đồng hồ PostgreSQL cho vòng đời OTP, từ chối ký tự điều khiển trong tên hiển thị và kiểm tra người nhận/nội dung thư. Bản sửa đã qua 61 test backend. Cấu hình khóa OTP được mô tả rõ theo trạng thái thiếu toàn bộ hoặc cấu hình không hợp lệ.

Theo đánh giá của Sol, điều kiện gửi được xác định ở lần đọc trạng thái trước SMTP. Ca đồng thời giữ sender giả lập đang xử lý, commit gửi lại qua HTTP, gây lỗi gửi lần đầu rồi xác nhận retry loại bỏ mã cũ và xóa ciphertext.

Sol review bổ sung trường hợp SMTP host rỗng dù Spring Boot đã tạo mail sender. Service và worker kiểm tra host trước khi phát hành hoặc gửi OTP. Ca HTTP/PostgreSQL dùng khóa và địa chỉ gửi hợp lệ chứng minh register/resend trả 503 và worker giữ job chưa gửi.

OTP được đối chiếu với email hiện tại sau khi khóa tài khoản bằng so sánh citext. Các trường chuỗi JSON từ chối số và boolean trước khi service xử lý. Ca tích hợp xác nhận mã cho địa chỉ cũ không kích hoạt địa chỉ mới và đầu vào sai kiểu không tăng attempts hay ghi tài khoản.

Giới hạn độ dài được đếm theo Unicode code point. Ca HTTP kiểm tra mật khẩu ngắn sáu ký tự ngoài BMP, biên 12/128, quá biên 129 và tên hiển thị 120/121, cùng trạng thái database sau từ chối.

Tên hiển thị giữ nguyên đầu vào có ký tự điều khiển để validator từ chối trước khi chuẩn hóa. Ca HTTP kiểm tra NUL giữa chuỗi, tab và xuống dòng ở đầu/cuối, cùng việc loại khoảng trắng thông thường.
