# Nhật ký triển khai khôi phục mật khẩu (M1.3)

## 2026-11-21

### Mục tiêu và phạm vi
- Triển khai phân hệ khôi phục mật khẩu thuộc Feature v1.1 FC-01.03 trên nhánh `codex/m1-password-recovery` từ mốc M1.2.
- 3 route anonymous POST tại `/api/v1/auth`:
  1. `POST /api/v1/auth/forgot-password`: tiếp nhận yêu cầu gửi OTP khôi phục.
  2. `POST /api/v1/auth/forgot-password/verify`: xác thực OTP và đổi lấy grant token `RESET_PASSWORD_GRANT` dùng một lần.
  3. `POST /api/v1/auth/reset-password`: đổi mật khẩu với grant token hợp lệ và thu hồi toàn bộ phiên đăng nhập của người dùng.
- Không kích hoạt hoặc mở khóa tài khoản; không phát hành access/refresh session trong luồng recovery.

### Cơ sở dữ liệu và Migration V009
- Migration `V009__queue_password_recovery.sql`:
  - Mở rộng ràng buộc `auth_tokens_purpose_check` cho phép `RESET_PASSWORD` và `RESET_PASSWORD_GRANT`.
  - Thêm ràng buộc `auth_tokens_reset_grant_owner` đảm bảo grant token luôn có `user_id IS NOT NULL` và `attempts = 0`.
  - Tạo chỉ mục phần `idx_auth_tokens_recovery_email` và `idx_auth_tokens_recovery_owner`.
  - Tạo chỉ mục duy nhất `uq_auth_tokens_reset_grant_digest` trên cột `token_hash` cho grant token.
  - Mở rộng `background_jobs_job_kind_check` cho phép `AUTH_RESET_PASSWORD`.
- Giữ nguyên 36 bảng nghiệp vụ và 36 JPA entities.

### Thiết kế bảo mật và đồng bộ Concurrency
- Thứ tự khóa đồng bộ: user row -> advisory xact lock trên normalized email (seed 142857) -> token rows -> background job.
- Mọi quyết định thời gian, hạn dùng và lease dùng `clock_timestamp()` của PostgreSQL.
- Intake gửi trả uniform `202 ACCEPTED` không phụ thuộc vào trạng thái tài khoản. Giới hạn tần suất 60 giây và 5 lần/giờ bảo vệ tài nguyên.
- OTP crypto: `VerificationCodeCrypto` mở rộng hỗ trợ mục đích typed `RESET_PASSWORD` và giữ nguyên tính tương thích ngược cho `VERIFY_EMAIL`.
- Grant token: 32 byte ngẫu nhiên an toàn, mã hóa Base64URL unpadded canonical 43 ký tự; database lưu SHA-256 digest dạng hex viết thường (64 ký tự). Từ chối mọi chuỗi non-canonical hoặc alias.
- Đổi mật khẩu: việc tính toán băm Argon2id (Argon2id hash computation) được thực hiện bên ngoài database transaction để tránh kéo dài thời gian giữ khóa. Thao tác ghi password_hash, tiêu thụ grant, vô hiệu hóa các recovery token cũ và thu hồi toàn bộ phiên đăng nhập (all-session revocation) được thực hiện đồng thời trong cùng một database transaction sau khi tái kiểm tra khóa, hạn dùng và tính hợp lệ của tài khoản (lock, expiry và eligibility revalidation). Tuyệt đối không cập nhật mật khẩu bên ngoài transaction.
- Khóa tài khoản hoặc đổi email sau khi cấp grant sẽ ngăn việc hoàn tất đổi mật khẩu.
- Worker quét và xử lý job `AUTH_RESET_PASSWORD` kiểm tra ràng buộc binding và high-water mark trước khi gửi mail, đồng thời kiểm tra lại ngay trước khi gửi qua SMTP bên ngoài transaction.

### Checkpoint đo kiểm có chủ đích và phân công mô hình (2026-11-21)
- Lệnh Maven JDK 27 có chủ đích (targeted):
  `./mvnw -B -Dmaven.repo.local=.tools/m2/repository -Dtest=PasswordRecoveryIntegrationTest,RegistrationUnavailableIntegrationTest,RegistrationEmptyMailHostIntegrationTest,VerificationCodeCryptoTest,PasswordResetGrantTokenTest test`
  hoàn thành với 28 tests, 0 failures, 0 errors, 0 skips.
- Các ca kiểm thử PostgreSQL 18.7 thực tế bao gồm nâng cấp database tạm thời từ V008 lên V009 với dữ liệu mẫu phiên/thư xác minh hiện hữu, cô lập fixture, cơ chế SMTP latch / retry job trễ (stale retry), rollback OTP/grant/session/reset và các ca kiểm thử tương tranh (concurrency) sẵn có trong bộ test suite đó.
- Bằng chứng có chủ đích (TARGETED evidence), không phải cổng phát hành hoàn chỉnh: Đây chỉ là bằng chứng kiểm thử mục tiêu cục bộ, không thay thế cho toàn bộ cổng phát hành (release gate). Quy trình bắt buộc đầy đủ `./mvnw verify`, kiểm thử frontend (`npm test`, `npm run lint`, `npm run build`, `npm run test:e2e`), kiểm định độc lập QA (Luna 5.6) và đánh giá cuối cùng (Sol 6.1 High) vẫn là điều kiện tiên quyết trước khi xuất bản / commit.
- Vận chuyển mail SMTP chỉ là transport giả lập có kiểm soát (controlled fake transport); kiểm thử browser E2E sử dụng xác thực giả lập (mock auth) và không chứng minh tích hợp xác thực thực tế giữa backend và frontend.
- Lịch sử profile thực hiện thực tế:
  - Các bước ban đầu được thực hiện với cấu hình Medium (Gemini Flash Medium).
  - Người dùng đã chỉ định rõ ràng cấu hình Flash High (`gemini-3.8-flash-high`) cho các sửa đổi sau đó.
  - GPT-6.1 Sol hiện tại đảm nhận lập kế hoạch và đánh giá độc lập cuối cùng (`reasoning_effort=high`, native Sol 6.1 High).
  - GPT-5.6 Luna đảm nhận kiểm toán độc lập QA (native Luna 5.6).

---

## 2026-11-22: QA Remediation Pass A (Strict Mail Payload & Fencing)

### Phân tích phản hồi QA và xác nhận False Positive
1. **False Positive về High-Water Mark SQL**:
   - Luna QA ghi nhận: "RecoveryMailEligibility.java:243-253 claims to check the newest previously bound OTP but does not require prev.user_id IS NOT NULL; a newer unbound challenge can incorrectly supersede an older challenge, violating the specified bound-token high-water rule."
   - Đánh giá kỹ thuật: Trong câu truy vấn high-water mark, điều kiện `prev.user_id = ?` nhận tham số `user.id()` là non-null UUID đã được khóa từ bảng `users`. Theo chuẩn SQL tam phân (three-valued logic), `prev.user_id = <non-null UUID>` đánh giá thành UNKNOWN/FALSE với mọi bản ghi có `prev.user_id IS NULL`. Do đó, các challenge chưa gán (unbound token) tuyệt đối không bao giờ thỏa mãn điều kiện này và không thể vô hiệu hóa challenge cũ. Việc thêm `prev.user_id IS NOT NULL` là hoàn toàn dư thừa (redundant predicate) và không phải là lỗi logic.
   - Kết quả thực nghiệm: Trong lần chạy có chủ đích thực tế trên JDK 27 / PostgreSQL (67 tests, 66 passed, 1 failure, 0 skipped), ca kiểm thử cụ thể `highWaterMarkProvesNewerUnboundDoesNotDisplaceOlderEligibleBinding()` đã PASS. Đây là bằng chứng per-test độc lập cho test case này, không phải là xác nhận PASS toàn bộ tính năng và không thay thế cho các cổng kiểm thử đầy đủ.

2. **Khắc phục thứ tự giải mã và cổng tiền kiểm tra payload (Pre-decrypt Shape Validation Gate)**:
   - Trước đây: `VerificationMailWorker` giải mã ciphertext trước khi `RecoveryMailEligibility` kiểm tra định dạng và phiên bản schema của payload.
   - Khắc phục: Xây dựng lớp phân tích cú pháp nghiêm ngặt `RecoveryMailPayload`:
     - Kiểm tra chính xác 3 trường: `schemaVersion`, `challengeId`, `ciphertext`.
     - `schemaVersion`: Bắt buộc là số nguyên numeric 1; từ chối chuỗi `"1"`, `null`, boolean, mảng, object, số thập phân `1.0`/`1.5`, hoặc phiên bản khác.
     - `challengeId`: Chuỗi UUID định dạng chuẩn viết thường (canonical lowercase UUID, độ dài 36 ký tự hex); trùng khớp với `idempotency_key` và id của token.
     - `ciphertext`: Chuỗi Base64URL unpadded canonical, độ dài giải mã từ 34 đến 128 byte; kiểm tra tính canonical bằng cách đối chiếu chuỗi ban đầu với chuỗi tái mã hóa (re-encoded match).
     - Phân tách rõ ràng mã lỗi dừng xử lý (sanitized terminal error code): lỗi định dạng payload tiền giải mã trả về `INVALID_PAYLOAD`; lỗi giải mã/xác thực GCM trả về `DECRYPT_FAILED`. Tuyệt đối không ghi nhật ký raw payload hoặc bí mật.
   - Nhánh `AUTH_VERIFY_EMAIL` hiện hữu được giữ nguyên cấu trúc và luồng giải mã độc lập, không bị ảnh hưởng bởi bộ lọc payload của password recovery.

3. **Tái kiểm tra toàn diện Payload và Lease Fencing ngay trước khi gửi thư (Pre-Send Revalidation Gate)**:
   - Trong `RecoveryMailEligibility.isEligibleToSend`:
     - Tái truy vấn `j.payload::text`, `j.attempts`, `t.expires_at`, `j.expires_at`, `t.token_hash`.
     - Xác thực lại định dạng và tính đồng nhất tuyệt đối giữa payload đã lưu trong DB và payload bất biến đã claim (`claimedPayload`). Ngăn chặn hoàn toàn việc gửi thư dựa trên ciphertext cũ hoặc payload bị sửa đổi giữa claim -> binding hoặc binding -> pre-send.
     - Kiểm tra tính bình đẳng về hạn dùng giữa token và job (`token.expires_at = job.expires_at`) và hạn dùng dương (`expires_at > created_at`).
     - Tái kiểm tra lease, trạng thái `RUNNING`, chủ sở hữu lease (`locked_by = owner`), `locked_until > now()` và số lần thử (`job.attempts = claimedAttempts`).
     - Tái xác thực HMAC (`crypto.matches`), email người nhận citext, liên kết tài khoản (`requested_by = user_id`) và người dùng ACTIVE đã xác minh tại thời điểm phát hành.
     - Tái kiểm tra high-water mark của token bound mới nhất.
   - Mọi thao tác cập nhật trạng thái lỗi ở worker hoặc eligibility đều có điều kiện ràng buộc `locked_by = ? AND locked_until > clock_timestamp()`, đảm bảo worker quá hạn lease (stale worker) không thể sửa đổi hoặc gửi nhầm job của worker mới.

### Báo cáo kiểm thử có chủ đích và khắc phục lỗi Fixture (Actual Run 67 tests & Validated Checkpoint 68 tests)
- Lịch sử thất bại trước đó: 67 tests, 66 passed, 1 failure, 0 skipped (FAIL - không phải release approval). Thất bại tại `tokenJobMismatchedExpiryRejectsBothBindingAndPreSendPreservingLeaseFence` do format jsonb và thứ tự pre-send fixture. Đã được phân tích và khắc phục triệt để.
- **Checkpoint kiểm thử Mail Remediation đã được xác thực (Controller Measured Checkpoint, 2026-11-22)**:
  - Lệnh chạy có chủ đích JDK 27 / PostgreSQL hoàn tất thành công: **68 tests, 0 failures, 0 errors, 0 skips, BUILD SUCCESS**.
  - Bằng chứng an toàn: `task/targeted-mail-safety-strict-claims.log`.
  - Phân bổ thực tế giữa các test class:
    1. `PasswordRecoveryMailSafetyIntegrationTest`: 12 tests.
    2. `RecoveryMailPayloadTest`: 10 tests.
    3. `PasswordResetGrantTokenTest`: 5 tests.
    4. `VerificationCodeCryptoTest`: 6 tests.
    5. `EmailRegistrationIntegrationTest`: 16 tests.
    6. `CombinedAuthenticationIntegrationTest`: 4 tests.
    7. `PasswordRecoveryIntegrationTest`: 15 tests.
  - Các lỗi compile và thất bại 67/66/1 trước đây thuộc về lịch sử và đã được giải quyết hoàn toàn.
  - Vệ sinh cơ học (mechanical cleanup): Loại bỏ 1 khoảng trắng thừa (trailing whitespace) duy nhất sau `CapturingMailSender capturer = ` tại dòng 398 trong fixture pre-send của `PasswordRecoveryMailSafetyIntegrationTest.java`. Toàn bộ logic kiểm thử của file này giữ nguyên tuyệt đối.

### Bằng chứng kiểm thử mới
1. `src/test/java/com/project/devsphere/identity/infrastructure/mail/RecoveryMailPayloadTest.java`:
   - Kiểm thử toàn diện parser `RecoveryMailPayload`: định dạng JSON hợp lệ, từ chối non-object, thừa/thiếu trường, sai kiểu `schemaVersion` (chuỗi, null, boolean, mảng, số thập phân 1.5/1.0, số nguyên khác 1), sai `challengeId` (chữ hoa, sai hex, sai độ dài, sai dấu ngoặc, lệch `idempotency_key`), sai `ciphertext` (rỗng, ký tự lạ, padding `=`, sai Base64URL, quá ngắn < 34 byte, quá dài > 128 byte).
2. `src/test/java/com/project/devsphere/PasswordRecoveryMailSafetyIntegrationTest.java`:
   - Khởi tạo Testcontainers PostgreSQL và cấu hình mail test có kiểm soát `CapturingMailSender`.
   - 12 ca kiểm thử hoàn tất xác thực trong checkpoint 68 tests:
     1. `numericSchema1SendsOnce`: Job hợp lệ gửi mail 1 lần duy nhất, scrub ciphertext, bind user.
     2. `preDecryptShapeErrorsRejectWithoutTransportAndSetInvalidPayloadReason`: 8 trường hợp payload lỗi định dạng bị từ chối ngay trước giải mã, không gửi thư, mã lỗi `INVALID_PAYLOAD`.
     3. `distinguishesPreDecryptShapeErrorFromDecryptError`: Phân biệt rõ `INVALID_PAYLOAD` (lỗi shape tiền giải mã) và `DECRYPT_FAILED` (shape hợp lệ nhưng hỏng AES-GCM tag).
     4. `persistedPayloadMutationBetweenClaimAndBindingOrPreSendRejectsStaleSend`: Sửa đổi payload/ciphertext giữa claim -> bind hoặc bind -> pre-send đều bị phát hiện và ngăn chặn gửi mail.
     5. `tokenJobMismatchedExpiryRejectsBothBindingAndPreSendPreservingLeaseFence`: Lệch hạn dùng giữa token và job từ chối cả bind và pre-send, bảo toàn lease fence.
     6. `fiveSmtpFailuresExhaustAttemptsAndScrubCiphertextWithIncreasingBackoff`: 5 lần lỗi SMTP liên tiếp với backoff tăng dần (10s, 20s, 30s, 40s), dừng tại lần 5 với `MAIL_EXHAUSTED` và xóa ciphertext, chu kỳ 6 không gửi lại.
     7. `staleLeaseOwnerCannotMutateNewerOwnersJobOrSend`: Chủ sở hữu lease cũ bị quá hạn không thể sửa đổi hay gửi job của chủ sở hữu mới.
     8. `highWaterMarkProvesNewerUnboundDoesNotDisplaceOlderEligibleBinding`: Minh chứng thực nghiệm khẳng định cáo buộc của QA là false positive: token mới hơn chưa bind không thể ngăn cản token cũ hoàn tất binding và gửi mã OTP.
     9. `highWaterMarkProvesNewerBoundTokenPreventsOlderJobFromRevivingOrSending`: Token mới hơn đã bind (kể cả đã tiêu thụ) ngăn cản hoàn toàn job cũ hồi sinh hoặc gửi mail (`SUPERSEDED_BY_NEWER_CHALLENGE`).
     10. `newerExpiredTokenPreservesTtlOrderAndAssertsNoRevival`: Đảm bảo quy tắc bảo toàn TTL thực tế, token cũ hết hạn bị từ chối đúng quy định.
     11. `nullKeyJobTerminatesInvalidPayloadWithoutTransportOrBinding`: Job có idempotency_key là null bị kết thúc với INVALID_PAYLOAD không gửi thư hay bind token.
     12. `strictAttemptAndOtpFenceRejectsInvalidInputsPreservingPositiveControlWithoutMutation`: Cùng chủ sở hữu nhưng sai/lệch attempt (-1, 0, 2, 6) hoặc OTP null/rỗng/sai không làm thay đổi database hay gửi thư; positive control strict attempt 1 và OTP hợp lệ vẫn hoạt động bình thường.

---

## 2026-11-22: QA Remediation Pass B (Grant Safety & Authentication Evidence)

### Mục tiêu và phạm vi
- Khắc phục các phát hiện QA độc lập của Luna 5.6 về thiếu hụt bằng chứng tích hợp cho grant token, account ineligibility, bearer authority, no-session recovery, luồng HTTP hoàn chỉnh và an toàn persistence.
- Triển khai bộ kiểm thử tích hợp chuyên biệt mới: `src/test/java/com/project/devsphere/PasswordRecoveryGrantSafetyIntegrationTest.java`.
- Tái sử dụng cấu hình Testcontainers PostgreSQL (`PasswordRecoveryIntegrationTest.configure`), cấu hình mail test có kiểm soát `TestMailConfig`, và `@AutoConfigureMockMvc` với chuỗi lọc Spring Security thực tế.
- Không thay đổi mã nguồn nghiệp vụ (production code), dependency, cấu hình ứng dụng hay Git.

### Thiết kế kiểm thử chi tiết (5 ca kiểm thử tích hợp trọng tâm)
1. **`uniformResetGrantInvalidRejectionsAcrossInvalidGrantFormsAndPurposes`**:
   - Thiết lập 13 trường hợp grant không hợp lệ:
     1. Expired-at-insertion: Grant hợp lệ nhưng hết hạn từ khi insert (legal past created_at và expires_at, không sửa đổi trigger bất biến).
     2. Already-used: Grant đã được tiêu thụ (legal past used_at).
     3. Canonical forged: Chuỗi Base64URL 43 ký tự chuẩn sinh bởi `PasswordResetGrantToken.generate()` nhưng không tồn tại trong database.
     4. Non-canonical alias (padding `=`): Base64URL có dấu padding `=`.
     5. Non-canonical alias (leading space): Grant có khoảng trắng ở đầu.
     6. Non-canonical alias (trailing space): Grant có khoảng trắng ở cuối.
     7. Non-canonical alias (invalid trailing bits): Thay đổi 2 bit thấp nhất của ký tự thứ 43 khiến chuỗi giải mã vẫn ra 32 byte nhưng tái mã hóa không trùng khớp.
     8. Unsupported string (empty): Chuỗi rỗng `""`.
     9. Unsupported string (whitespace): Chuỗi chỉ chứa khoảng trắng `"   "`.
     10. Unsupported string (too short): Chuỗi quá ngắn (`"tooShort"`).
     11. Unsupported string (too long): Chuỗi quá dài (44 ký tự).
     12. Unsupported string (illegal characters): Ký tự không thuộc bảng mã Base64URL (`!`).
     13. Wrong token purpose: Grant digest được lưu dưới mục đích `VERIFY_EMAIL` thay vì `RESET_PASSWORD_GRANT`.
   - Khẳng định tính đồng nhất (uniformity): Mọi trường hợp đều trả về HTTP 400 Bad Request, tiêu đề `Cache-Control: no-store`, không phát hành bất kỳ cookie nào (`Set-Cookie` vắng mặt), thân lỗi ProblemDetail chuẩn với `code = "RESET_GRANT_INVALID"` và thông điệp cố định.
   - Bảo mật bí mật: Thân phản hồi không phản chiếu (no reflection) grant thô hay mật khẩu mới.
   - Tính toàn vẹn nghiệp vụ: Bản ghi người dùng mục tiêu (password_hash, version, status, updated_at) không bị thay đổi; mật khẩu cũ vẫn đăng nhập thành công.

2. **`accountIneligibilityAndBearerCredentialAuthorityAreStrictlyEnforced`**:
   - Scenario 2A (Tài khoản bị khóa): Người dùng bị khóa (`status = 'LOCKED'`) sau khi cấp grant sẽ bị từ chối đổi mật khẩu với HTTP 400 `RESET_GRANT_INVALID`; tài khoản giữ nguyên trạng thái `LOCKED`, không tự động mở khóa, grant không bị tiêu thụ.
   - Scenario 2B (Email thay đổi): Người dùng đổi email sau khi cấp grant không thể dùng grant cũ (vốn gắn với email ban đầu) để reset; từ chối với HTTP 400 `RESET_GRANT_INVALID`.
   - Scenario 2C (Quyền hạn Bearer Credential của Grant):
     - Grant bản thân là một bearer reset credential: một yêu cầu gửi grant hợp lệ của User A nhưng đính kèm Bearer token và refresh cookie của User B (đã đăng nhập thực tế) sẽ đổi mật khẩu thành công cho User A và thu hồi toàn bộ phiên của User A.
     - Xác thực tính cô lập tuyệt đối của User B (caller): Mật khẩu của User B không bị thay đổi, phiên của User B vẫn hoạt động bình thường (`GET /api/v1/auth/me` với Bearer token của User B trả về 200 OK với đúng ID của User B), các token khác của User B trong `auth_tokens` giữ nguyên vẹn.
     - Ngược lại, nếu caller User B gửi một grant không hợp lệ cho User A: yêu cầu thất bại với 400 `RESET_GRANT_INVALID`, cả hai tài khoản đều không bị ảnh hưởng.

3. **`noSessionRecoveryRevokesZeroSessionsAndPreservesExistingRevocationsAndOtherTokens`**:
   - Người dùng không có phiên đăng nhập nào (`auth_sessions` hoạt động = 0) thực hiện quy trình khôi phục: gửi OTP -> worker xử lý -> đọc mail có kiểm soát -> verify OTP -> đổi grant -> reset mật khẩu.
   - HTTP phản hồi 200 OK với `revokedSessions = 0`, tiêu đề `Cache-Control: no-store`, không phát hành cookie phiên/đăng nhập mới.
   - Tài khoản người dùng vẫn giữ nguyên trạng thái `ACTIVE` và mốc `email_verified_at`.
   - Bản ghi phiên cũ đã bị thu hồi trước đó (`auth_sessions` lịch sử với `revoked_at` trong quá khứ) được bảo toàn nguyên vẹn về mốc thời gian (`issued_at`, `expires_at`, `revoked_at`).
   - Token thuộc mục đích khác (`VERIFY_EMAIL`) của người dùng không bị ảnh hưởng (`used_at IS NULL`).
   - Mật khẩu cũ bị vô hiệu hóa; mật khẩu mới đăng nhập thành công qua HTTP.

4. **`completeRealHttpFlowFromRegistrationToRecoveryResetAndReauthentication`**:
   - Chứng minh toàn bộ luồng tích hợp HTTP thực tế từ đầu đến cuối không dùng mock auth hay SessionService trực tiếp:
     1. `POST /api/v1/auth/register` (secure) trả về 201 Created với `challengeId` và `userId` (`status = 'PENDING'`).
     2. Worker xử lý chu kỳ và gửi email xác minh qua `CapturingMailSender`.
     3. Đọc mã OTP 6 chữ số từ email gửi tới đúng địa chỉ người nhận.
     4. `POST /api/v1/auth/verify-email` xác minh thành công (`status = 'ACTIVE'`).
     5. Lấy boot CSRF token từ `GET /api/v1/auth/csrf` (cookie `__Host-devsphere-csrf` và header `X-CSRF-TOKEN`).
     6. `POST /api/v1/auth/login` với mật khẩu ban đầu và CSRF token hợp lệ, nhận `accessToken` và cookie `__Secure-devsphere-refresh`.
     7. Gọi `GET /api/v1/auth/me` với Bearer access token, xác nhận định danh người dùng.
     8. Gửi yêu cầu recovery `POST /api/v1/auth/forgot-password` (anonymous JSON, không cookie) nhận 202 ACCEPTED.
     9. Worker xử lý chu kỳ và gửi email recovery OTP tới đúng người nhận.
     10. Đọc mã recovery OTP từ email và gọi `POST /api/v1/auth/forgot-password/verify` nhận `resetGrant`.
     11. Gọi `POST /api/v1/auth/reset-password` với `resetGrant` và mật khẩu mới nhận 200 OK và `revokedSessions >= 1`.
     12. Kiểm chứng xác thực sau khi reset:
         - Mật khẩu cũ bị từ chối khi đăng nhập (`401 INVALID_CREDENTIALS`).
         - Access token cũ bị từ chối trên `GET /api/v1/auth/me` (`401 Unauthorized`).
         - Refresh cookie cũ bị từ chối trên `POST /api/v1/auth/refresh` (`401 INVALID_SESSION`).
         - Mật khẩu mới đăng nhập thành công với boot CSRF mới, phát hành access token và refresh cookie mới.
         - Access token mới truy cập `GET /api/v1/auth/me` thành công với đúng `userId`.

5. **`grantPersistenceDigestSafetyAndRedactedDiagnosticsWithAdversarialStrings`**:
   - An toàn lưu trữ cơ sở dữ liệu: Cột `token_hash` trong `auth_tokens` chỉ lưu trữ chuỗi băm hex SHA-256 viết thường 64 ký tự chuẩn (`^[0-9a-f]{64}$`), tuyệt đối không lưu grant thô, OTP thô hay mật khẩu thô. Các trường tất định của bảng `users` (`password_hash`, `email`, `display_name`) không chứa các chuỗi bí mật này.
   - Khử định danh chẩn đoán (Redacted diagnostics): Kiểm tra phương thức `toString()` của các bản ghi `PasswordResetGrantToken`, `ForgotPasswordRequest`, `VerifyRecoveryRequest`, `VerifyRecoveryResponse`, `ResetPasswordRequest`, `VerifyRecoveryResult` đều trả về chuỗi đã được che giấu bí mật (ví dụ `[redacted]`).
   - Kháng chuỗi độc hại (Adversarial strings): Gửi các chuỗi SQL injection (`' OR 1=1; --`, `'; DROP TABLE auth_tokens; --`), XSS script tag, HTML tag, CRLF header injection, null byte/unicode, chuỗi định dạng format specifier, chuỗi payload lớn (3000 ký tự) và JSON lồng nhau vào trường `resetGrant`. Mọi trường hợp đều bị từ chối an toàn với HTTP 400 `RESET_GRANT_INVALID`, `Cache-Control: no-store`, không phát hành cookie và không phản chiếu lại chuỗi độc hại trong thân phản hồi; cấu trúc cơ sở dữ liệu và bảng được bảo vệ nguyên vẹn.

### Trạng thái thực thi và Cổng kiểm duyệt tiếp theo
- Mã nguồn kiểm thử `PasswordRecoveryGrantSafetyIntegrationTest.java` (5 tests ban đầu) đã được bổ sung vào checkout.
- Checkpoint đo kiểm thực tế đạt được: PASS 73 tests, 0 failures, 0 errors, 0 skips (bằng chứng `targeted-grant-safety.log`).
- Các bước tiếp theo: Tiến hành Remediation Pass C giải quyết các yêu cầu kiểm thử tương tranh, thứ tự serialization và hủy job trước reset.

---

## 2026-11-22: QA Remediation Pass C (Transactional Concurrency, Cancellation & Grant Safety Corrections)

### Measured Checkpoint (Actual Run 73 tests, 2026-11-22)
- **Lệnh chạy có chủ đích JDK 27 / PostgreSQL trước Remediation Pass C**:
  - Kết quả: **PASS: 73 tests, 0 failures, 0 errors, 0 skips, BUILD SUCCESS**.
  - Phân bổ thực tế giữa 8 test classes:
    1. `PasswordRecoveryMailSafetyIntegrationTest`: 12 tests.
    2. `RecoveryMailPayloadTest`: 10 tests.
    3. `PasswordResetGrantTokenTest`: 5 tests.
    4. `VerificationCodeCryptoTest`: 6 tests.
    5. `EmailRegistrationIntegrationTest`: 16 tests.
    6. `CombinedAuthenticationIntegrationTest`: 4 tests.
    7. `PasswordRecoveryGrantSafetyIntegrationTest`: 5 tests.
    8. `PasswordRecoveryIntegrationTest`: 15 tests.
  - Tệp bằng chứng xác thực: `targeted-grant-safety.log`.
- **Trạng thái bộ kiểm thử mới**: Các ca kiểm thử bổ sung trong Pass C (`PasswordRecoveryConcurrencyIntegrationTest` và cập nhật `PasswordRecoveryGrantSafetyIntegrationTest`) được ghi nhận là **đang chờ thực thi (pending until executed)** bởi controller.
- **Tuyên bố ranh giới**: Không đưa ra bất kỳ khẳng định nào về tích hợp SMTP thực tế (live provider) hoặc browser backend authentication integration (vận chuyển mail SMTP chỉ là transport có kiểm soát `CapturingMailSender`; kiểm thử browser E2E sử dụng mock authentication).

### Hiệu chỉnh bằng chứng kiểm thử Grant Safety (`PasswordRecoveryGrantSafetyIntegrationTest.java`)
Đã hoàn tất 4 hiệu chỉnh cụ thể theo yêu cầu source inspection mà không viết lại cấu trúc file:
1. **True Non-canonical Alias Fixture**:
   - Trước đây fixture thay ký tự thứ 43 thành `'A'` hoặc `'B'`, vốn tạo ra chuỗi canonical khác (kiểm tra unknown grant thay vì alias).
   - Đã sửa: Sử dụng bảng mã Base64URL chuẩn, đảo bit padding thấp (bit 0 của chỉ số 6-bit của ký tự thứ 43: `lastCharIndex ^ 1`).
   - Khẳng định: `aliasDecoded` bằng chính xác `canonicalDecoded` (32 bytes), chuỗi tái mã hóa canonical trùng khớp với grant ban đầu trong khi chuỗi alias khác biệt.
   - Thử nghiệm HTTP: Chuỗi alias bị từ chối với HTTP 400 `RESET_GRANT_INVALID` và grant canonical gốc trong database vẫn giữ nguyên `used_at IS NULL`.
2. **Deterministic Semantic Assertions cho Persistence Digest**:
   - Loại bỏ việc tìm kiếm substring ngẫu nhiên của OTP 6 chữ số trong các trường `password_hash`, `email`, `display_name` (tránh xung đột ngẫu nhiên với chuỗi muối Argon2 hoặc UUID).
   - Thay thế bằng kiểm chứng tất định: Chụp trạng thái người dùng trước và sau khi cấp OTP/grant, khẳng định tính toàn vẹn và bình đẳng tuyệt đối giữa các trường trước/sau; kiểm tra băm mật khẩu bằng xác thực Argon2 thực tế (`encoder.matches`).
   - Xác thực lưu trữ OTP trong `auth_tokens`: Kiểm tra tính bình đẳng tuyệt đối giữa digest lưu trữ và mã băm HMAC có kiểm soát (`crypto.hash`), không bao giờ bằng plaintext OTP, và thỏa mãn `crypto.matches`. Grant lưu trữ dưới dạng SHA-256 digest hex 64 ký tự.
   - Giữ nguyên toàn bộ các ca kiểm thử khử định danh (`toString()`) và chuỗi độc hại adversarial.
3. **Focused HTTP Reset Case với Không có Bản ghi Session nào (Zero Total Session Rows)**:
   - Bảo toàn ca kiểm thử `noSessionRecoveryRevokesZeroSessions...` hiện hữu (trường hợp phiên lịch sử đã thu hồi).
   - Bổ sung ca kiểm thử chuyên biệt `resetWithZeroTotalSessionRowsReturnsZeroRevokedSessionsAndPreservesStateWithoutSessionCreation`:
     - Người dùng có đúng 0 bản ghi trong `auth_sessions` cả trước và sau khi reset mật khẩu qua HTTP.
     - Phản hồi HTTP 200 OK, tiêu đề `Cache-Control: no-store`, `revokedSessions = 0`.
     - Không phát hành cookie phiên/đăng nhập nào (`Set-Cookie` vắng mặt).
     - Trạng thái người dùng giữ nguyên `ACTIVE` và mốc `email_verified_at`; băm mật khẩu được cập nhật thành công.
     - Mật khẩu cũ bị từ chối đăng nhập (401); mật khẩu mới đăng nhập thành công mà không phát sinh phiên tự động ngoài ý muốn.
4. **Cô lập Test Peer Counter**:
   - Chuyển `peerCounter` thành `private static final AtomicInteger` dùng chung giữa các test instance của class, loại bỏ phụ thuộc in-memory limiter giữa các phương thức kiểm thử độc lập mà không can thiệp vào hành vi production của limiter.

### Bộ kiểm thử Concurrency & Cancellation chuyên biệt (`PasswordRecoveryConcurrencyIntegrationTest.java`)
Đã triển khai bộ kiểm thử tích hợp chuyên biệt mới với 6 ca kiểm thử bao phủ toàn diện các kịch bản tương tranh giao dịch:
1. **`twoSimultaneousCorrectOtpExchangesProduceExactlyOneGrantAndOneInvalidCodeError`**:
   - Hai luồng đồng thời gửi mã OTP hợp lệ qua HTTP `POST /api/v1/auth/forgot-password/verify` với peer IP độc lập.
   - Kết quả: Đúng 1 luồng nhận HTTP 200 OK (chứa `resetGrant` hợp lệ và `expiresAt`), đúng 1 luồng nhận HTTP 400 Bad Request (`RESET_CODE_INVALID`, không để lộ raw grant, không cookie).
   - Cơ sở dữ liệu: Đúng 1 grant mới được tạo (`RESET_PASSWORD_GRANT`), OTP chỉ bị tiêu thụ đúng 1 lần (`used_at IS NOT NULL`), số lần đoán sai `attempts` giữ nguyên bằng 0.
2. **`subsequentValidOtpExchangeRetiresPriorUnusedGrantAtomically`**:
   - Đổi mã OTP lần 1 lấy Grant 1. Sau đó đổi mã OTP lần 2 (đáp ứng cooldown 60 giây bằng fixture hợp lệ phát hành 70 giây trước).
   - Kết quả: Grant 1 bị thu hồi nguyên tử (`used_at IS NOT NULL`), Grant 2 hoạt động bình thường (`used_at IS NULL`).
   - Grant 1 cũ không thể đổi mật khẩu (HTTP 400 `RESET_GRANT_INVALID`); Grant 2 mới đổi mật khẩu thành công (HTTP 200 OK).
   - Toàn bộ grant và token `VERIFY_EMAIL` của người dùng khác cũng như token khác của chính người dùng được bảo toàn nguyên vẹn.
3. **`refreshCommittedFirstIsSubsequentlyIncludedInResetRevocation`**:
   - Điều phối tất định thứ tự serialization Refresh -> Reset với thứ tự khóa user-first.
   - Fixture connection giữ khóa hàng `FOR UPDATE` trên `auth_sessions` của phiên hiện tại.
   - Luồng HTTP Refresh khóa bản ghi `users`, sau đó bị chặn khi yêu cầu khóa phiên (quan sát qua `pg_blocking_pids` trên PID fixture).
   - Luồng HTTP Reset bắt đầu, bị chặn khi yêu cầu khóa bản ghi `users` đang do Refresh nắm giữ (quan sát qua `pg_blocking_pids` trên PID của Refresh).
   - Nhả khóa fixture: Refresh commit trước (xoay vòng phiên và cấp session mới), sau đó Reset unblock và commit (thu hồi toàn bộ phiên của người dùng, bao gồm cả phiên vừa xoay vòng).
   - Khẳng định DB: Toàn bộ phiên đều có `revoked_at IS NOT NULL`, cột `rotated_at` của phiên mới giữ nguyên `NULL` (không bị ghi đè sai lệch). Cả refresh token cũ, refresh token mới xoay vòng, access token cũ và mới đều bị từ chối truy cập.
4. **`resetCommittedFirstRejectsConcurrentRefreshWithoutSurvivingSession`**:
   - Điều phối tất định thứ tự serialization Reset -> Refresh với thứ tự khóa user-first.
   - Fixture connection giữ khóa hàng `FOR UPDATE` trên `auth_sessions` của phiên hiện tại.
   - Luồng HTTP Reset khóa bản ghi `users`, cập nhật mật khẩu, và bị chặn tại câu lệnh `UPDATE auth_sessions` (quan sát qua `pg_blocking_pids` trên PID fixture).
   - Luồng HTTP Refresh bắt đầu, bị chặn khi yêu cầu khóa bản ghi `users` đang do Reset nắm giữ (quan sát qua `pg_blocking_pids` trên PID của Reset).
   - Nhả khóa fixture: Reset commit trước (thu hồi phiên). Refresh unblock, kiểm tra thấy phiên đã bị thu hồi (`revoked_at IS NOT NULL, rotated_at IS NULL`), từ chối với HTTP 401 `INVALID_SESSION`, không phát hành bất kỳ phiên nào sống sót.
   - Khẳng định DB: Tổng số phiên giữ nguyên 1, phiên đã bị thu hồi, `rotated_at` giữ nguyên `NULL`.
5. **`unboundRecoveryRequestQueuedBeforeResetIsCancelledAndPostResetRequestSucceeds`**:
   - Yêu cầu recovery chưa bind (unbound OTP) được hàng đợi trước thời điểm reset mật khẩu thành công.
   - Sau khi reset, bản ghi token này được đánh dấu tiêu thụ (`used_at IS NOT NULL`).
   - Chu kỳ quét sau của worker claim job này: phát hiện token đã bị tiêu thụ, kết thúc job với `FAILED` (`TOKEN_UNUSABLE`), xóa ciphertext và tuyệt đối không gửi thư qua SMTP hay tạo grant.
   - Các OTP cũ đã bind và grant cũ trước reset đều bị vô hiệu hóa và từ chối khi gọi verify/reset.
   - Job recovery của người dùng khác trong hàng đợi không bị ảnh hưởng và gửi thư bình thường.
   - Yêu cầu recovery hợp lệ phát hành sau reset được xử lý, gửi mail, verify và reset mật khẩu thành công (tôn trọng cooldown và giới hạn tần suất).
6. **`transactionFailureDuringResetRollsBackPasswordHashGrantConsumptionAndSessionsTogether`**:
   - Tiêm lỗi giao dịch tạm thời (temporary trigger trên `auth_sessions`).
   - Yêu cầu reset mật khẩu thất bại: Khẳng định rollback nguyên tử đồng thời của băm mật khẩu `password_hash`, trạng thái tiêu thụ grant `used_at`, và trạng thái thu hồi phiên `revoked_at`.
   - Sau khi gỡ bỏ fixture lỗi, thực hiện thử lại bình thường (ordinary retry) với chính grant đó thành công trọn vẹn.

### Quy chuẩn JDBC và Quản lý Tài nguyên
- Sử dụng UUID có kiểu (typed UUID), `ResultSet.getString` cho trường citext, và tham số thời gian `OffsetDateTime` chuẩn UTC.
- Mọi connection, executor pool và database trigger tạm thời đều được giải phóng/rollback nghiêm ngặt trong khối `finally`.
- Không sử dụng sleep mù quáng; điều phối tương tranh dựa trên quan sát trạng thái khóa thực tế với `pg_blocking_pids` trong khoảng thời gian có giới hạn (bounded condition polling).

### Trạng thái hiện tại và Cổng kiểm duyệt tiếp theo
- Các thay đổi đã được ghi nhận vào đúng 3 tệp được phép:
  1. `src/test/java/com/project/devsphere/PasswordRecoveryConcurrencyIntegrationTest.java` (tạo mới)
  2. `src/test/java/com/project/devsphere/PasswordRecoveryGrantSafetyIntegrationTest.java` (chỉ sửa 4 điểm evidence cụ thể)
  3. `docs/backend/password-recovery-implementation-log.md` (ghi nhật ký tiến trình)
- Trạng thái: Sẵn sàng để Controller chạy kiểm thử trên JDK 27 / PostgreSQL cho bộ 8 lớp hiện hữu cùng lớp Concurrency mới.
- Toàn bộ các cổng kiểm tra tiếp theo (Maven verify, frontend unit/lint/build/E2E, native Luna QA và Sol final review) thuộc quyền kiểm soát của Controller trước khi commit/push.
