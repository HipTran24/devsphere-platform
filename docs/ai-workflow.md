# Quy trình Sol → Flash → Luna → Sol

Sol trong Codex App lập kế hoạch. Runner gọi Flash High qua Antigravity CLI, chạy các
kiểm tra dự án; Codex App điều phối Luna và Sol qua native agents theo request do
runner tạo. Luna kiểm tra chất lượng toàn dự án và diff, Sol review độc lập.
Chỉ sau ba chốt verify/QA/review PASS trên cùng snapshot
mới cho commit. Runner không push, không tự gộp nhánh và không tự chuyển model.

## Chuẩn bị một lần

- Python 3.9+, Git, Node/npm, JDK 27, Docker đang chạy và Chrome cho Playwright.
- Cài dependency frontend bằng `npm ci` trong `frontend` nếu chưa có.
- Codex App có quyền gọi native agents `gpt-5.6-luna` và `gpt-6.1-sol`. Các worker
  này được gọi từ chat, không cần khởi tạo Codex CLI app-server lồng trong sandbox.
  Không thay model nếu App từ chối. CLI vẫn có thể dùng khi bỏ cấu hình
  `worker_transports` tương ứng, nhưng phải smoke lại riêng, không suy từ native access.
- Antigravity CLI tại `.tools/ai-workflow/agy` (bản local đã xác minh SHA512), hoặc
  `agy` trong PATH. Chạy CLI trong terminal để hoàn tất đăng nhập nếu được yêu cầu.
  Kiểm tra `agy models` có `gemini-3.8-flash-high`.
- Chọn GPT-6.1 Sol trong Codex App. Runner không thay model của chat hiện tại.

```sh
python3 scripts/ai/workflow.py doctor
python3 scripts/ai/workflow.py smoke
```

`smoke` gọi Flash thật, tạo hai request native cho Luna/Sol và trả exit 2 để Codex App
dispatch các agent. Sau khi lưu kết quả thật, chạy lại với ID vừa được in, ví dụ:

```sh
python3 scripts/ai/workflow.py smoke smoke-1795170000000000000
```

ID trên chỉ minh họa; phải dùng ID của lần chạy thực tế. Chỉ exit 0 khi cả ba model
đã trả đúng challenge. Exit 2 là đang chờ agent, không phải PASS. Không dùng mock
để chứng nhận truy cập. Antigravity ghi runtime log trong thư mục run bằng
`--log-file`; có thể cảnh báo không lưu được lịch sử dưới home trong sandbox,
nhưng chỉ response SUCCESS thật mới chứng nhận được lượt gọi.
Không tắt sandbox hoặc dùng flag bỏ qua mọi phê duyệt. Đăng nhập App và CLI cần
kiểm tra riêng; không sao chép token giữa chúng.
Flash triển khai dùng `--mode accept-edits --sandbox`: cho phép sửa file trong
workspace, giữ giới hạn terminal và phê duyệt command. Tài khoản đã thử không hỗ
trợ `auto`; không để CLI âm thầm rơi về default rồi coi triển khai đã hoàn tất.

## Bàn giao cho native agent trong Codex App

Các bước `qa`, `review` và smoke native tạo `<stage>-native-request.json` gồm model,
prompt, output schema, snapshot, nonce và thời hạn 30 phút. Controller trong chat
gọi đúng model bằng native agent, độc lập context và chỉ đọc; native Sol dispatch yêu cầu
reasoning_effort=high và fork_turns=none, là final reviewer độc lập chỉ đọc riêng biệt với controller. Controller lưu nguyên
kết quả thực tế trong `<stage>-native-result.json` theo envelope:

```json
{
  "transport": "codex-app",
  "stage": "qa",
  "model": "gpt-5.6-luna",
  "snapshot": "COPY_FROM_CURRENT_REQUEST",
  "nonce": "COPY_FROM_CURRENT_REQUEST",
  "agent_task": "/root/ACTUAL_DISPATCHED_AGENT",
  "result": {"response": "ACTUAL_AGENT_RESPONSE"}
}
```

Ví dụ `result.response` trên dành cho smoke. QA/review phải trả object theo schema
trong request: verdict, reviewed_snapshot, scope, findings và blockers. Chạy lại lệnh
đang chờ để runner xác minh envelope. Model, nonce, snapshot sai hoặc request hết hạn
đều bị chặn. Một Terminal độc lập không tự gọi được native tool của Codex App.
Phê duyệt QA/review chỉ dùng một lần: kết quả đã nhận được lưu trong `native-history/`;
chạy lại bước đó tạo nonce mới và cần agent trả lời mới. Chạy lại implement/verify
thu hồi QA/review cũ ngay cả khi source chưa đổi. Smoke chỉ chứng nhận kết nối,
không cấp quyền QA/review/commit cho Project.
Controller chịu trách nhiệm kết quả đến từ đúng agent; envelope là bằng chứng quy
trình local, không phải chữ ký mật mã. Không tự điền PASS hoặc mô phỏng response.

## Một nhiệm vụ triển khai

Sol tạo file kế hoạch bên ngoài nguồn, ví dụ `.ai-runs/plans/login.md`. Ghi rõ:
yêu cầu, phạm vi file được sửa, điều không làm, tiêu chí nghiệm thu, test cần chạy.
Sau đó chạy từng bước và đọc kết quả trước bước tiếp theo:

```sh
python3 scripts/ai/workflow.py start login --plan .ai-runs/plans/login.md
python3 scripts/ai/workflow.py implement login
python3 scripts/ai/workflow.py verify login
python3 scripts/ai/workflow.py qa login
python3 scripts/ai/workflow.py review login
python3 scripts/ai/workflow.py status login
python3 scripts/ai/workflow.py commit login --message 'feat: implement login'
```

Normal task yêu cầu checkout sạch lúc bắt đầu. Commit bước cuối stage toàn bộ thay
đổi của nhiệm vụ, vì vậy không thêm việc khác vào cùng checkout. Thay đổi mới sau
QA/review làm mất hiệu lực bằng chứng. Nếu Luna hoặc Sol phát hiện lỗi, gọi lại
`implement`, sau đó `verify`, `qa`, `review`. Các findings cũ được chuyển cho Flash.
Không đổi HEAD trong khi chạy. Kết quả/log nằm ở `.ai-runs/<task>/`, được Git bỏ qua.

Khi chạy `implement`, runner lưu marker `implement_attempt` (gắn với version 1, base HEAD
và initial_snapshot). Marker này là bằng chứng duy nhất xác nhận nguồn gốc sạch đã được
thừa nhận (admitted-origin), hoàn toàn không đồng nghĩa với việc tiến trình đã khởi chạy,
file đã sửa hay triển khai thành công/được duyệt. Khi worker gặp lỗi, timeout hoặc ngắt quãng
giữa chừng để lại workspace dirty, lần chạy lại tiếp theo (prospective retry) vẫn được chấp nhận
nếu HEAD không đổi và task đã có marker hợp lệ (hoặc kỷ lục PASS legacy). Các lần thất bại lịch sử
không có bằng chứng nguồn gốc sạch vẫn bị chặn; không tự ý tạo/sửa marker thủ công trong `state.json`
hay backfill từ log. Marker đơn lẻ không cấp quyền cho verify hay commit; sau khi sửa code, quy trình
bắt buộc phải chạy fresh verify, Luna QA và Sol review độc lập trên snapshot mới. State local
là chốt quy trình nội bộ, không phải chứng chỉ bảo mật chống giả mạo.

Để kiểm tra toàn dự án hiện tại, bao gồm code chưa commit, dùng audit:

```sh
python3 scripts/ai/workflow.py start baseline --audit --plan .ai-runs/plans/baseline.md
python3 scripts/ai/workflow.py verify baseline
python3 scripts/ai/workflow.py qa baseline
python3 scripts/ai/workflow.py review baseline
```

Audit không được triển khai hoặc commit. Một audit ban đầu giúp lập danh sách lỗi
có sẵn. Luna vẫn phải đọc toàn dự án và phần thay đổi trong mỗi nhiệm vụ, không chỉ
đọc báo cáo tự đánh giá của Flash. Sol cần đánh giá findings có căn cứ, không chỉ
dựa vào chữ PASS của model. Checks phải có exit code thật; QA phải phát hiện test
bị skip, trường hợp thiếu test và giới hạn mock của E2E.

## Chốt kiểm tra và giới hạn

- SHA256 gắn kết quả với HEAD, nội dung, đường dẫn, quyền file và symlink. File
  tracked bị xóa và file mới không bị ignore cũng được tính. Build output bị ignore.
- Chỉ một runner giữ lock; timeout dừng cả nhóm tiến trình. Run lỗi không cấp PASS.
- QA/review dùng JSON schema: verdict, snapshot, scope, findings và blockers.
  PASS sai snapshot, thiếu trường, có blockers hoặc thay đổi nguồn đều bị chặn.
- Test runner workflow, backend verify, unit test, lint, production build và
  desktop/mobile E2E đều bắt buộc. Runner chặn suite rỗng hoặc có test bị skip;
  Playwright bật `--forbid-only` để chặn test vô tình bị giới hạn bằng `.only`.
- Cấu hình model/checks nằm ở `scripts/ai/config.json`, phải được review như code.
- Đây là chốt quy trình local, không phải ranh giới bảo mật chống tác nhân độc hại:
  người có quyền sửa repo có thể gọi Git trực tiếp hoặc sửa runner. AGENTS.md là
  chỉ dẫn; muốn áp đặt trên toàn nhóm cần branch protection và CI của repository.
- Không sửa file thủ công trong lúc runner chạy. Git commit hooks có thể sửa file;
  nếu dùng hook tự sửa code, phải kiểm thử/review lại nội dung hook đã sửa.
- Nếu tiến trình bị kill cứng, lock có thể còn. Đọc PID trong `.ai-runs/workflow.lock`,
  chỉ xóa lock khi chắc chắn runner đó đã dừng. Chạy lại bước thất bại; không sửa
  `state.json` để bỏ qua chốt.

## Kiểm thử runner

```sh
python3 -m unittest discover -s tests/ai -v
```

Tests dùng Git repo tạm và worker giả để kiểm tra cơ chế chặn; chúng không chứng
minh ba model đã truy cập được. Luôn báo riêng kết quả runner, checks dự án và smoke thật.

Tài liệu chính thức: [Antigravity headless](https://antigravity.google/docs/cli/headless/),
[Codex non-interactive](https://learn.chatgpt.com/docs/non-interactive-mode),
[model Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna).
