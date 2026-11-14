# P6 — Job nền, tệp ảnh và thông báo

Ngày thực hiện: 13/11/2026. Chỉ triển khai và kiểm thử trong repo/local; không kết nối hoặc thay đổi Railway/production.

## Sử dụng

- Mọi tài khoản hoạt động có **Thông báo** trong menu. Hộp thư chỉ chứa thông báo gửi cho chính tài khoản đó, có lọc chưa đọc, phân trang và đánh dấu đã đọc. Web/PWA polling 45 giây, dừng khi component được tháo khi đăng xuất.
- Vai có `task:view` có **Công việc và lời hẹn**. Có thể tự đặt việc đến hạn; giao người khác yêu cầu `task:assign` theo phạm vi A/R/T hiện có. Chỉ người được giao hoàn tất công việc đang mở. Việc được giao tạo thông báo trong cùng giao dịch.
- Vai có `config:manage` phạm vi A có **Cấu hình vận hành** và lịch sử job. Backend kiểm lại quyền từ DB; thay đổi cấu hình cần lý do, version và được audit.
- Trong chi tiết lượt ghé/đơn sell-out có **Ảnh đính kèm**. JPEG/PNG được nén trên trình duyệt/mobile web trước khi tải. Mã ảnh đã hoàn tất dùng trong bước ghi nhận thiếu/hỏng. API cũng hỗ trợ ảnh ticket cho client có chức năng ticket; P4 hiện chưa có màn hình ticket đầy đủ.

## Job và chống chạy trùng

- `BackgroundScheduler` quét mỗi phút; `APP_JOBS_ENABLED=false` tắt scheduler cho replica chỉ phục vụ HTTP. Có thể để nhiều replica bật scheduler; PostgreSQL là nơi quyết định ai được chạy.
- `DistributedJobRunner` lấy `pg_try_advisory_xact_lock(hashtextextended(tenant + job_name, 0))` **trên chính connection/giao dịch ghi nghiệp vụ**. Không dựa vào mutex JVM. Hai instance không thực hiện cùng loại job của một tenant đồng thời.
- Khóa tenant/job được giữ tới COMMIT/ROLLBACK. Bản ghi `SUCCEEDED` được ghi cùng giao dịch nghiệp vụ và có unique index tenant/job/run_key, tránh chạy lại lượt thành công. Lỗi rollback nghiệp vụ, vẫn giữ log `FAILED` riêng, lần quét sau được thử lại. Worker mất kết nối sẽ được đánh dấu `ABANDONED` khi worker khác lấy được khóa.
- `background_job_run` lưu bắt đầu, kết thúc, số bản ghi, chi tiết đếm và mã lỗi; log ứng dụng có run ID/tenant/job/status. Không ghi token, URL ký hoặc nội dung nhạy cảm vào thông báo lỗi job.
- `NIGHTLY`: khóa theo ngày Việt Nam, lịch giờ/phút đọc từ cấu hình tenant. Tạo snapshot ngày T vào T+1, khớp ba bên bằng nghiệp vụ đối soát cũ, thử chốt những ngày đang mở tới T. Không tự xác nhận nhận hàng hoặc tự xác nhận đối soát thay người dùng.
- Chỉ chốt khi không còn đơn chưa hoàn tất, đủ đối soát `CONFIRMED` cho các NPP có quan hệ hiệu lực và không còn ngoại lệ mở. Dùng khóa bảng `order_si/order_line` trong giao dịch chốt để chặn bản ghi mới/thay đổi chen giữa kiểm tra và ghi CLOSED. Khóa này tạm thời chặn ghi đơn nhập trên các tenant khác trong lúc chốt; cần đo tải thực trước khi tối ưu sang cơ chế khóa thống nhất ở mọi đường ghi.
- Làm sạch: đánh dấu upload PENDING quá hạn thành EXPIRED; xóa refresh token hết hạn quá thời gian lưu. Chỉ gỡ liên kết giữa các token cũ đều đã hết hạn; giữ token còn được phiên sống tham chiếu. Không xóa giao dịch, audit hoặc tenant.
- `REMINDERS`: việc/lời hẹn đến hạn, ticket sắp tới SLA hoặc đã quá SLA, đơn chờ xác nhận/quá hạn nhận hàng, NPP không đặt quá chu kỳ. Mỗi sự kiện có khóa chống trùng theo đối tượng/hạn. Leo thang tới NPP_OWNER sau khi đã nhắc đủ khoảng chờ; không đổi trạng thái đơn. NPP chưa đặt được nhắc lại sau mỗi chu kỳ cấu hình, tính từ lần đặt gần nhất hoặc lúc bắt đầu quan hệ.
- Hai scheduler cũ không có khóa đã được thay thế. Một ngày thành công không bị ghi thêm log SKIPPED sau mỗi phút. Lịch hiện chạy bù ngày liền trước khi ứng dụng khởi động sau giờ cấu hình; chưa tự tạo snapshot cho mọi ngày vắng dịch vụ kéo dài. Ngày cũ đã có kỳ mở vẫn được xét chốt.

## Object storage

Backend không ghi tệp người dùng vào filesystem container. AWS SDK Java v2 dùng S3 API, có endpoint tùy chỉnh/path-style cho dịch vụ tương thích. Bucket phải private. Biến môi trường đã thêm vào `.env.example`:

| Biến | Cách dùng |
| --- | --- |
| `S3_ENDPOINT` | Endpoint HTTPS của R2/S3 tương thích; để trống khi dùng endpoint AWS mặc định |
| `S3_REGION` | Region AWS; R2 thường dùng `auto` |
| `S3_BUCKET` | Bucket private đã tạo |
| `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY` | Credential giới hạn đúng bucket/prefix; nếu không đặt key, SDK dùng credential provider mặc định của AWS |
| `S3_PATH_STYLE` | Theo yêu cầu nhà cung cấp, `true` cho emulator local |

Luồng ảnh:

1. Client xin tùy chọn giới hạn, nén ảnh, gửi metadata và owner (`VISIT`, `TICKET`, `ORDER_SO`). Backend kiểm lại tài khoản, quyền và phạm vi mới nhất từ DB.
2. Backend tạo bản ghi PENDING và URL PUT ngắn hạn cho key `quarantine/<tenant>/<uuid>`. Client gửi đúng signed headers và độ dài Blob; không gửi API bearer tới storage.
3. Client gọi complete. Backend kiểm HEAD/dung lượng thực, chỉ đọc tối đa giới hạn, xác định đúng định dạng JPEG/PNG, kiểm kích thước trước decode (tối đa 20 triệu pixel/cạnh 16384).
4. Decode rồi re-encode JPEG để loại metadata; tạo ảnh chính và thumbnail theo cấu hình. Hai key mới `images/...` và `thumbnails/...` khác key upload, nên URL PUT cũ không sửa được ảnh đã duyệt. Sau đó mới ghi READY. Complete cùng ID được gọi lại an toàn.
5. Chỉ ảnh READY có thể tải bằng URL GET 5 phút. Tệp LEGACY không tự được tin cậy/mở công khai. Ảnh nhận hàng phải READY, của chính người tải và đúng đơn; sau khi đã liên kết vào xác nhận nhận hàng, các bên có quyền xem đơn có thể xem ảnh liên quan. Ảnh chưa liên kết và ảnh lượt ghé/ticket tiếp tục bị giới hạn tenant/phạm vi.

Không có credential/bucket thì endpoint tải lên trả `STORAGE_NOT_CONFIGURED` (503); các chức năng khác vẫn chạy. Chưa chuyển tệp LEGACY cũ vì chưa có danh sách nguồn/kho thực.

Thiết lập CORS bucket cho đúng origin web, ví dụ thay domain bên dưới bằng domain đang dùng:

```json
[
  {
    "AllowedOrigins": ["https://<frontend-domain>"],
    "AllowedMethods": ["PUT", "GET", "HEAD"],
    "AllowedHeaders": ["*"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 300
  }
]
```

Thiết lập lifecycle xóa prefix `quarantine/` sau ít nhất một ngày. Không áp lifecycle này cho `images/` hay `thumbnails/`. Cần theo dõi và đối chiếu orphan của ảnh chính nếu storage đã ghi thành công nhưng hoàn tất DB thất bại; P6 chưa tự xóa ảnh chính orphan. Không tự tạo bucket, thay IAM/CORS/lifecycle hoặc xóa object trên tài khoản thật.

## Expo push và giới hạn mobile

Repo hiện chỉ có Mobile Sales dạng web/PWA, chưa có app Expo/native, Expo projectId hay credential FCM/APNs. Không coi polling web là Expo push đã chạy trên thiết bị thật.

- Backend có API đăng ký/hủy thiết bị, ràng buộc `deviceId` với JWT, tối đa 5 thiết bị đang hoạt động/tài khoản, không tự chuyển token sang tài khoản khác.
- Trigger notification tạo `push_delivery` cho các thiết bị của đúng người nhận trong cùng giao dịch. Đổi trạng thái đơn, giao việc và các nhắc quá hạn đều đi qua bảng notification này. Thông báo từ đơn tenant khác được sở hữu bởi tenant của người nhận.
- Worker gửi Expo Push Service, lưu ticket, đợi receipt, thử lại có backoff/giới hạn khi lỗi mạng/provider. `DeviceNotRegistered` vô hiệu hóa thiết bị. Không gửi nội dung giao dịch nhạy cảm qua push; payload chỉ có notification ID để client mở hộp thư đã xác thực.
- Push mặc định tắt trong cấu hình tenant. `EXPO_ACCESS_TOKEN` là credential phía server nếu bật bảo vệ push của Expo project. Bật `push_enabled` sau khi native app/project và quyền hệ điều hành đã sẵn sàng.
- `packages/mobile/expoNotifications.ts` là adapter cho app Expo: truyền module `expo-notifications`, transport đã xác thực, EAS projectId và deviceId phiên. Có xin quyền, Android channel, đăng ký token, xử lý token đổi, mở thông báo, dọn listener và unregister trước logout. Backend logout cũng tắt thiết bị theo phiên refresh.
- App native cần gọi adapter khi người dùng bật nhận thông báo, gọi unregister trước logout và gắn điều hướng `onOpen`. Chưa gắn vào một app native không tồn tại trong repo, chưa thử gửi FCM/APNs thật. Chưa xử lý mở app từ trạng thái bị tắt hoàn toàn; native app cần đọc `getLastNotificationResponseAsync` theo hướng dẫn Expo khi tích hợp.
- Push có thể gửi lại nếu provider đã nhận nhưng connection/DB mất trước khi lưu ticket; đây là cơ chế ít nhất một lần, không cam kết push đúng một lần. Receipt thành công là provider đã nhận, không chứng minh người dùng đọc.

## Cấu hình trong DB

Migration V12 tạo `runtime_config_defaults` và khởi tạo `tenant_config.config_key='p6.runtime'` cho tenant hiện có/mới. Default nghiệp vụ nằm trong migration, không fallback bằng số rải rác trong scheduler.

Các nhóm: bật/lịch job đêm, nhắc trước hạn, thời gian leo thang, SLA ticket, chu kỳ NPP, ngưỡng cảnh báo chênh lệch, ngưỡng cảnh báo cần duyệt, thời hạn xác nhận/việc thiếu-hỏng, dung lượng/expiry/kích thước ảnh, thời gian lưu token và bật/số lần thử push. API kiểm kiểu, khóa lạ và giới hạn hợp lý; cập nhật có optimistic locking/audit.

`approval_threshold_vnd` quyết định cảnh báo `approvalRequired` và thông báo tới người duyệt toàn tenant khi đơn vào PENDING_APPROVAL. Không tự duyệt hoặc mở rộng quyền duyệt. `variance_warning_percent` được dùng trong báo cáo đối soát thực; field cũ `warningOverFivePercent` giữ tên để tương thích API, nhưng giá trị được tính theo ngưỡng cấu hình hiện tại.

## API mới

| Endpoint | Chức năng |
| --- | --- |
| GET `/api/v1/operations/settings` | Cấu hình/version; chỉ config:manage A từ DB |
| PUT `/api/v1/operations/settings` | Toàn bộ value + version + reason |
| GET `/api/v1/operations/jobs?page=&size=` | Lịch sử job tenant hiện tại |
| GET `/api/v1/notifications?page=&size=&unreadOnly=` | Hộp thư riêng |
| POST `/api/v1/notifications/{id}/read`, `/read-all` | Đọc, gọi lại an toàn |
| POST `/api/v1/notifications/devices` | deviceId + expoToken |
| DELETE `/api/v1/notifications/devices/{deviceId}` | Hủy thiết bị của chính mình |
| GET `/api/v1/attachments/options` | Giới hạn ảnh tenant |
| POST `/api/v1/attachments/presign` | ownerType/ownerId/fileName/contentType/sizeBytes |
| POST `/api/v1/attachments/{id}/complete` | Kiểm ảnh/tạo thumbnail/READY |
| GET `/api/v1/attachments/{id}/download?thumbnail=` | URL GET private |
| GET `/api/v1/attachments?ownerType=&ownerId=` | Tối đa 100 ảnh READY liên quan |
| GET/POST `/api/v1/tasks` | Xem phân trang/tạo công việc |
| POST `/api/v1/tasks/{id}/assign`, `/{id}/complete` | Giao/hoàn tất có version |
| PUT `/api/v1/interactions/{id}/follow-up` | dueAt hoặc null để đặt/bỏ lời hẹn của tương tác do mình ghi |

Các endpoint được sinh trong `/api/v1/openapi`, yêu cầu bearer token. Không có API công khai để chạy tùy ý job, đổi tenant đích hoặc lấy storage key.

## Kiểm thử

- Kết quả: **97 test backend đạt, không lỗi/skip**, gồm toàn bộ 14 test PostgreSQL P5 và 13 test PostgreSQL P6, cùng 3 test xử lý ảnh; **16 test frontend đạt**. Build production mặc định, kiểm tra kiến trúc/không import mock và E2E hồi quy P4 cho bốn giao diện đã qua. E2E P6 API thật đã qua hộp thư/đánh dấu đọc, lưu cấu hình, tạo việc sinh thông báo, nén ảnh ở client, PUT S3 bằng URL ký không rò bearer, thumbnail, layout mobile và 401 khi chưa đăng nhập.
- PostgreSQL Docker riêng: migration V1–V12 từ DB trống và validate lần sau; tranh chấp khóa hai worker, chạy lại, rollback/lưu lỗi/thử lại; nhắc trùng, leo thang sau lần nhắc, giữ nguyên trạng thái đơn; chốt ngày đã xác nhận và từ chối đơn chưa hoàn tất; cleanup phiên/upload; inbox tenant/recipient, cấu hình/audit/version, thu hồi quyền dù JWT cũ còn grant.
- Object storage kiểm thử dùng Moto S3 emulator localhost qua AWS SDK và HTTP PUT/GET thật: presign, HEAD, kiểm dung lượng/MIME, ảnh chính/thumbnail, complete lặp, ảnh giả, hết hạn, tenant isolation. Đây không phải chứng nhận kết nối R2/AWS của người dùng. MinIO image không tải được từ registry trong môi trường này nên không dùng làm kết quả nghiệm thu.
- Expo worker được kiểm ticket/receipt, lỗi tạm thời, backoff và DeviceNotRegistered bằng phản hồi giả lập có chủ đích, không gửi tới người nhận thật. Adapter native có test cấp quyền/đăng ký/hủy.
- E2E `npm run test:p6` dùng API/PostgreSQL/S3 emulator local, không stub luồng API/ảnh. Các ảnh chụp QA nằm ở `backend/target/p6-ui/`.
- Hai lỗi SQL cũ ảnh hưởng P6 đã được sửa tối thiểu tại `VisitService`/`BusinessAccessService`: thêm khoảng trắng khi nối WHERE/filter/ORDER BY; có test PostgreSQL hồi quy danh sách/phạm vi ảnh lượt ghé.

Lệnh kiểm:

```powershell
npm test
npm run build
npm run check:architecture
npm run check:p4
npm run test:p4
mvn -f backend/pom.xml verify
# P6_TEST_DB_URL = jdbc:postgresql://127.0.0.1:<port>/synera_p6
# P6_TEST_DB_USER / P6_TEST_DB_PASSWORD: DB kiểm thử riêng.
# P6_S3_ENDPOINT = http://127.0.0.1:<port> của emulator, tuyệt đối không dùng bucket thật.
# P6_E2E_PASSWORD chỉ cho fixture local; P6_TEST_API_URL trỏ localhost API.
# Build với VITE_API_URL đúng API kiểm thử trước npm run test:p6;
# sau đó build lại không đặt VITE_API_URL để khôi phục cấu hình mặc định.
```

Working tree trước P6 đã có P3/P4/P5 chưa commit. Không reset/gộp những thay đổi đó, không coi P6 là đã triển khai hoặc hoàn tất các màn hình P4 còn thiếu.

Tài liệu kỹ thuật tham chiếu: [AWS SDK presigned URL](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html), [Expo gửi push/ticket/receipt](https://docs.expo.dev/push-notifications/sending-notifications/), [Expo nhận thông báo](https://docs.expo.dev/push-notifications/receiving-notifications/).
