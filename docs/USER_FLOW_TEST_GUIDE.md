# Hướng dẫn chạy luồng nghiệp vụ và kiểm thử theo role

Tài liệu này dành cho QA/dev chạy **local hoặc môi trường test riêng**. Không chạy seed, test, nhập dữ liệu mẫu hay thao tác nghiệm thu trên production. Tài khoản và dữ liệu demo không phải tài khoản/dữ liệu thật.

## 1. Chuẩn bị môi trường

### Điều kiện

- Node.js 22+, Corepack/pnpm và dependency của repository.
- Java 25+, Maven 3.9+ và PostgreSQL để chạy API.
- Docker Desktop (Linux containers), Edge headless (hoặc `BROWSER_CHANNEL`) cho P7.
- Với test P5/P6/P7, dùng database/container/emulator kiểm thử riêng như mô tả trong các tài liệu giai đoạn. Không trỏ các biến test tới DB hay dịch vụ production.

### Cài dependency và chạy frontend

Trong PowerShell, tại thư mục gốc repo:

```powershell
corepack pnpm install --frozen-lockfile
$env:VITE_API_URL = "http://127.0.0.1:8080"
$env:VITE_USE_MOCK = "false"
npm run dev
```

Mở `http://127.0.0.1:5173/`. Đăng nhập bằng tài khoản đã provision trong database local. Frontend thật đọc role/quyền từ `/api/v1/auth/me`; không có role switcher hoặc đăng ký công khai. Đăng xuất rồi đăng nhập tài khoản khác để đổi role. Token ở bộ nhớ nên tải lại trang sẽ yêu cầu đăng nhập lại.

### Chuẩn bị API và dữ liệu demo

Khởi tạo PostgreSQL local theo [README.md](../README.md) (mục **Local database**) và [backend/README.md](../backend/README.md). Chỉ dùng DB local/dev mới:

```powershell
docker compose up -d db
$env:DB_URL = "jdbc:postgresql://127.0.0.1:5432/synera"
$env:DB_USER = "synera"
$env:DB_PASSWORD = "synera_local_only"
$env:AUTH_JWT_SECRET = "<local-only-secret-it-nhat-32-byte>"
$env:APP_ENV = "dev"
$env:DB_ENVIRONMENT = "dev"
.\backend\scripts\db.ps1 -Task migrate
.\backend\scripts\db.ps1 -Task seed:system
```

Nếu cần seed demo:

```powershell
$env:SEED_DEMO = "true"
$env:SEED_DEMO_PASSWORD = "<mat-khau-demo-tu-chon>"
.\backend\scripts\db.ps1 -Task seed:demo
```

Không có mật khẩu mặc định trong repository. Các email demo (nếu được seed) nằm trong [`demo-seed.json`](../backend/src/main/resources/seed/demo-seed.json); hãy dùng mật khẩu do người chạy seed tự đặt. Nếu database đã có user nhưng không rõ mật khẩu, provision/reset theo quy trình local; không suy đoán credential.

Chạy backend trong terminal riêng sau khi cấu hình biến môi trường:

```powershell
mvn -f backend/pom.xml spring-boot:run
```

Kiểm tra frontend/API đang gọi đúng nhau, đăng nhập thành công và `GET /api/v1/auth/me` trả về tenant, roles, permissions đúng trước khi chạy checklist.

## 2. Thứ tự chạy kiểm thử tự động

Chạy theo phạm vi cần nghiệm thu; không phải mọi lệnh đều dùng cùng một DB:

```powershell
npm test
npm run build
npm run check:architecture
npm run check:p4
npm run test:p4
npm run test:p5
npm run test:p6
npm run test:p7
```

| Lệnh | Bao phủ chính | Lưu ý |
| --- | --- | --- |
| `npm test` | Unit test auth, API client và hàng đợi đơn mobile | Không thay thế kiểm thử nghiệp vụ end-to-end |
| `npm run test:p4` | Login/redirect, menu theo quyền, phân trang, xử lý lỗi và responsive của bốn cổng | Dùng phản hồi API kiểm thử; không chứng minh tích hợp DB thật |
| `npm run test:p5` | Import dữ liệu nền qua UI/API và DB test | Cần API/DB fixture local riêng theo `docs/P5_STATUS.md` |
| `npm run test:p6` | Notification/task/attachment, ảnh với S3 emulator | Cần API/DB và S3 emulator local theo `docs/P6_STATUS.md`; không dùng bucket thật |
| `npm run test:p7` | Luồng đơn và DB thật, RBAC 16 role × 109 quyền, tenant isolation, khóa kỳ và benchmark | Cần Docker, Java/Maven, Edge, tài nguyên test riêng; runner tự tạo và dọn container. Không dùng `-SkipPerformance` để kết luận nghiệm thu đầy đủ |

`npm run test:p7` là bộ nghiệm thu nghiệp vụ/API và RBAC; nó **không** tự động đi qua mọi màn hình của mọi role. Kết hợp với checklist UI bên dưới. Xem [P4_STATUS.md](P4_STATUS.md), [P5_STATUS.md](P5_STATUS.md), [P6_STATUS.md](P6_STATUS.md), [P7_STATUS.md](P7_STATUS.md) để biết giới hạn từng giai đoạn và biến test cần thiết.

## 3. Checklist chung cho từng role

Lặp lại các bước sau với từng tài khoản/role:

1. Đăng xuất, đăng nhập lại và xác nhận đúng tên user, tenant, role hiển thị ở phần nhận diện.
2. Xác nhận hệ thống chuyển tới đúng cổng: NSX → Web NSX; NPP → Portal NPP; điểm bán → Cổng điểm bán. Role Sales/giám sát có quyền Mobile Sales thì kiểm thêm `/#/sales`.
3. Ghi lại các mục menu nhìn thấy; mỗi mục phải tương ứng với quyền trong `/auth/me`. Kiểm tra dữ liệu chỉ thuộc tenant và phạm vi được cấp (A/R/T/O).
4. Thử một thao tác được phép trên fixture có thể hoàn tác/seed lại; xác nhận dữ liệu, trạng thái, lịch sử và thông báo sau thao tác.
5. Thử một thao tác không được phép, nếu có endpoint/UI cho thao tác đó: UI không hiển thị nút hoặc API trả 403. Thử truy cập ID thuộc tenant/phạm vi khác: dữ liệu không được trả lộ (thường là 404/403 theo endpoint).
6. Ghi nhận kết quả PASS/FAIL, user/role/tenant, dữ liệu fixture và lỗi. Không ghi password, token, secret hoặc dữ liệu production vào báo cáo.

Menu là điều hướng tiện ích, không phải ranh giới bảo mật. Quyền phải được kiểm tra lại tại API; ẩn nút trên UI một mình không đủ để kết luận RBAC đạt.

## 4. Luồng nghiệp vụ end-to-end

Chạy tuần tự trên dữ liệu test mới, vì các bước sau phụ thuộc trạng thái được tạo ở bước trước.

### A. Sell-in trong hạn mức, nhận hàng và đối soát/chốt kỳ

1. Đăng nhập `NPP_OWNER` (hoặc role được phép tạo sell-in), mở **Đơn sell-in**, tạo đơn từ sản phẩm hợp lệ và gửi.
2. Đăng nhập `NSX_ORDER`, tiếp nhận đơn và kiểm tra chi tiết, số lượng, giá do server tính.
3. Đăng nhập role có quyền duyệt (trong fixture P7: `NSX_LEADER`), duyệt đơn trong hạn mức.
4. Với vai được phép xử lý kho NSX, cập nhật số lượng xuất. Đăng nhập NPP xác nhận số lượng thực nhận.
5. Dùng `NSX_ACCT` tạo kỳ phù hợp và đối soát; xác nhận từ cả NSX và NPP. Kiểm tra hóa đơn/snapshot, trạng thái đối soát và lịch sử.
6. Chạy job đêm qua runner P7; xác nhận chỉ đơn đủ điều kiện được chốt, kỳ đóng và chạy lặp không tạo lịch sử chốt trùng.

**Kỳ vọng:** trạng thái đi theo workflow server; đơn nhận đủ tạo hóa đơn đúng một lần; không thể chốt nếu thiếu xác nhận/đối soát hoặc còn ngoại lệ mở.

### B. Vượt hạn mức và maker-checker

1. Tạo sell-in vượt hạn mức bằng NPP; kỳ vọng đơn vào `HELD_CREDIT`.
2. Thử duyệt vượt bằng `NSX_ASM`, rồi bằng chính người tạo: cả hai phải bị từ chối.
3. Duyệt bằng `NSX_RSM` trong phạm vi được giao hoặc `NSX_LEADER`; xác nhận đơn chuyển trạng thái và audit ghi nhận.
4. Kiểm tra payload không thể giả danh người tạo hoặc vượt scope bằng cách sửa ID/tenant.

**Kỳ vọng:** chỉ người có quyền/phạm vi phù hợp và khác người tạo mới duyệt; thao tác nhạy cảm được audit.

### C. Sell-out, xác nhận thiếu hàng và giao bù

1. Đăng nhập `NSX_SALES` trên Mobile Sales, mở đơn sell-out, chọn NPP/điểm bán thuộc tuyến và sản phẩm; yêu cầu giá server trước khi gửi.
2. Đăng nhập `NPP_OWNER`/`NPP_STAFF`, xác nhận số lượng đáp ứng thấp hơn số đặt và cập nhật xuất kho.
3. Đăng nhập `OUTLET_OWNER`/`OUTLET_STAFF`, xác nhận thực nhận thấp hơn số xuất; ghi số hỏng nếu có.
4. Xác nhận phát sinh ticket/công việc theo cấu hình. Tạo yêu cầu giao bù/đổi trả cho lượng thiếu/hỏng.
5. NPP quyết định yêu cầu, giao bù; điểm bán xác nhận nhận bù.

**Kỳ vọng:** đơn không đóng chỉ vì yêu cầu được duyệt; chỉ đóng sau khi lượng nhận đủ. Ticket/việc liên quan đóng đúng lúc, không đóng nhầm việc độc lập.

### D. Mobile offline và gửi lại an toàn

1. Trong Mobile Sales, tạo đơn sell-out và lưu **Đơn chờ gửi**.
2. Kiểm tra nháp gắn với đúng tài khoản/tenant; thử offline/online hoặc mô phỏng mất phản hồi sau khi server đã commit.
3. Gửi lại cùng nháp. Nếu giá server thay đổi, người dùng phải xác nhận giá mới trước khi gửi.

**Kỳ vọng:** retry dùng cùng Idempotency-Key và chỉ tạo một đơn; không mất nháp khi lỗi mạng. Đây là PWA/web mobile, không phải kiểm chứng ứng dụng native iOS/Android.

### E. Kỳ đã chốt và phiếu điều chỉnh

1. Sau khi kỳ đóng, thử sửa trực tiếp đơn, dòng hàng, snapshot hoặc kỳ: phải bị chặn.
2. `NSX_ACCT` lập phiếu điều chỉnh có lý do; chính người lập thử duyệt phải bị từ chối.
3. Một người có quyền duyệt khác (fixture: `NSX_LEADER`) duyệt phiếu.
4. Kiểm tra phiếu đã duyệt bất biến, báo cáo chính thức phản ánh điều chỉnh, báo cáo vận hành vẫn bảo toàn số gốc.

### F. Danh mục, tuyến, nhập liệu và vận hành

- **Danh mục:** role được cấp tạo/cập nhật SKU, quy đổi đơn vị, bảng giá/khuyến mãi/voucher; kiểm workflow gửi duyệt/kích hoạt và kết quả tính giá server. Không dùng dữ liệu demo làm giá thật.
- **Tổ chức/tuyến/MCP:** role quản lý trong scope tạo/cập nhật đơn vị, tuyến, điểm bán/tuyến; `NSX_SALES` kiểm tra danh sách điểm bán đúng tuyến, lượt ghé, check-in/out; không tự chấp thuận ngoại lệ GPS.
- **Tồn/sell-out/kế hoạch/chỉ tiêu:** tạo/cập nhật dữ liệu trong phạm vi; so sánh tồn và tổng hợp sau thao tác.
- **Import nền:** role cho phép chọn loại import, tải mẫu, preview có lỗi/cảnh báo, xác nhận ghi sau khi sửa. Xác nhận preview chưa ghi DB; lô có lỗi phải rollback toàn bộ. Ma trận loại import và giới hạn xem [P5_STATUS.md](P5_STATUS.md).
- **Công việc/lời hẹn:** role có `task:view` xem/tạo; giao người khác chỉ khi có `task:assign`; người nhận hoàn tất. Kiểm tra thông báo gắn với người nhận.
- **Thông báo:** kiểm lọc chưa đọc, phân trang, đánh dấu đã đọc/đọc tất cả; tài khoản không xem thông báo của người khác.
- **Ảnh đính kèm:** chỉ kiểm với S3 emulator local; xác nhận ảnh hợp lệ hoàn tất thành READY, ảnh giả/không đúng owner hoặc tenant bị từ chối. Thiếu cấu hình storage thì tính năng ảnh báo rõ lỗi, không giả vờ thành công.
- **Cấu hình vận hành/dung lượng:** chỉ role đúng quyền truy cập; thay đổi cấu hình phải có lý do/version và audit. Kiểm tra chức năng quản trị dung lượng bằng DB test.

## 5. Danh sách role và trọng tâm kiểm thử

Tên vai/quyền chuẩn lấy từ seed RBAC; quyền cuối cùng phụ thuộc scope và cấu hình tài khoản. Dùng dữ liệu `/auth/me` làm kết quả thực tế, không suy luận chỉ từ tên role.

| Role | Cổng/phạm vi cần kiểm | Trọng tâm kiểm thử |
| --- | --- | --- |
| `SYS_ADMIN` | Quản trị nền tảng | Truy cập console/dung lượng và vận hành nền tảng được cấp; kiểm tra **không** đọc đơn, giá, công nợ nghiệp vụ của tenant. |
| `NSX_LEADER` | Web NSX, toàn NSX | Báo cáo/tổng quan và duyệt ngoại lệ lớn: vượt hạn mức, giá/khuyến mãi hoặc điều chỉnh theo quyền; không tự duyệt phiếu do chính mình lập. |
| `NSX_RSM` | Web NSX, vùng được giao (R) | Xem dữ liệu vùng, quản lý đội/địa bàn theo quyền và duyệt ngoại lệ trong vùng; thử truy cập vùng khác phải bị chặn. |
| `NSX_ASM` | Web NSX, vùng được giao (R) | Quản lý tuyến/MCP và ngoại lệ trong vùng; kiểm thử không có quyền duyệt vượt hạn mức trong kịch bản P7. |
| `NSX_SUP` | Web NSX/Mobile Sales, tuyến được giao (T) | Theo dõi sales/MCP, lượt ghé và ngoại lệ GPS trong tuyến; không nhìn thấy tuyến khác. |
| `NSX_ADMIN` | Web NSX, tenant NSX (A) | Quản trị dữ liệu/cấu hình, SKU/tổ chức và import các loại được cho phép; maker không tự duyệt nội dung mình soạn. Không gán vai cao hơn cấp được phép. |
| `NSX_ORDER` | Web NSX | Tiếp nhận/kiểm tra đơn sell-in, xử lý phần workflow/kho theo quyền; kiểm tra không tự có quyền phê duyệt ngoại lệ vượt hạn mức nếu chưa được cấp. |
| `NSX_CS` | Web NSX | Kiểm tra quyền/chức năng chăm sóc khách hàng và xử lý ticket theo phạm vi. Ticket UI đầy đủ chưa được nghiệm thu ở P4; dùng API/test khi cần. |
| `NSX_TRADE` | Web NSX | Soạn khuyến mãi/voucher và xử lý đề xuất hỗ trợ theo quyền; xác minh duyệt tách biệt với người soạn. |
| `NSX_ACCT` | Web NSX | Hạn mức, hóa đơn/công nợ, thanh toán/khấu trừ, đối soát, kỳ và phiếu điều chỉnh; maker-checker bắt buộc. |
| `NSX_SALES` | Mobile Sales, tuyến được giao (T) | Check-in/out, lượt ghé, giá và tạo sell-out cho điểm bán trên tuyến; thử gửi điểm bán ngoài tuyến phải bị chặn. |
| `NPP_OWNER` | Portal NPP, tenant NPP (A) | Sell-in, duyệt đơn sell-out, kế hoạch, công nợ, quản lý user NPP và import điểm bán/user/tồn được phép. |
| `NPP_STAFF` | Portal NPP, tenant NPP (A) | Xác nhận đáp ứng, xuất giao, tồn/sell-out và tạo đơn; import chỉ các loại cho phép, không quản lý user nếu không có quyền riêng. |
| `NPP_ACCT` | Portal NPP | Xem công nợ, hóa đơn, thanh toán, đối soát và báo cáo đã chốt; không làm thao tác kho/duyệt ngoài quyền. |
| `OUTLET_OWNER` | Cổng điểm bán, tenant điểm bán | Đặt hàng, xác nhận nhận hàng, phản ánh thiếu/hỏng và quản lý nhân viên điểm bán theo quyền. |
| `OUTLET_STAFF` | Cổng điểm bán, tenant điểm bán | Đặt hàng/nhận hàng thay chủ cửa hàng; kiểm tra không có chức năng quản lý người dùng. |

Một số role có nhiều quyền khác nhau theo workbook RBAC hoặc có dữ liệu fixture khác nhau. Không kết luận hai role tương đương chỉ vì thấy cùng một menu.

## 6. Kiểm thử quyền và phạm vi

P7 là kiểm tra tự động chuẩn cho ma trận role/quyền: đối chiếu 16 role × 109 permission (1.744 cặp), gồm quyền import P5, rồi kiểm tra cô lập tenant, sửa ID/JWT và truy cập ngoài tuyến. Để xác nhận một role cụ thể:

1. Ghi nhận `roles` và `permissions` từ `/api/v1/auth/me`.
2. So sánh menu/nút với quyền tương ứng, nhưng không xem menu là bằng chứng duy nhất.
3. Thử đọc danh sách/chi tiết trong scope được cấp và một bản ghi ngoài scope.
4. Gọi thao tác được cho phép rồi thao tác bị cấm bằng UI/API; kiểm status và nội dung lỗi.
5. Với quyền nhạy cảm, kiểm tra audit log và maker/checker; với dữ liệu tài chính, kiểm lại bất biến sau khi kỳ đã đóng.

Nếu ma trận quyền thay đổi có chủ đích, cập nhật nguồn RBAC và snapshot oracle theo quy trình trong [P7_STATUS.md](P7_STATUS.md), review diff, rồi chạy lại `mvn -f backend/pom.xml verify` và `npm run test:p7`.

## 7. Ghi nhận kết quả và giới hạn hiện tại

Mỗi lượt nghiệm thu nên ghi: commit/revision, ngày chạy, môi trường (local/test), test runner, role/tenant, mã fixture, PASS/FAIL, bằng chứng trạng thái DB/API và lỗi. Không đính kèm thông tin xác thực.

Các màn/API chưa có UI hoàn chỉnh không được đánh dấu “đã test giao diện” chỉ vì API hoặc unit test đạt. Tham khảo [P4_STATUS.md](P4_STATUS.md) về các phần còn thiếu, trong đó có một số dashboard/báo cáo, quản lý tuyến/MCP nâng cao, ticket UI, upload ảnh thật và các trải nghiệm quản trị. Mobile trong repository là PWA. Chỉ kiểm nghiệm các tích hợp ngoài (Railway, bucket thật, push thiết bị native) khi có kế hoạch riêng, môi trường được phê duyệt và tài khoản test chuyên dụng.
