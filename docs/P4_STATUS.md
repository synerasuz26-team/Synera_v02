# P4 — Kiểm tra và tiếp tục chuyển frontend sang API

Ngày kiểm tra: 13/11/2026. Phạm vi: P4 trong prompt đính kèm. Chưa đánh dấu hoàn thành toàn bộ P4, chưa sang P5, chưa triển khai và chưa kết nối/ghi database Railway.

## Hiện trạng trước khi sửa

- Cả bốn giao diện import trực tiếp `packages/mock-data`; chưa có lớp service nghiệp vụ để giữ nguyên chữ ký như mô tả trong prompt.
- Auth đã gọi backend nhưng đăng nhập chỉ hiện thông báo thành công, chưa chuyển vào ứng dụng; chưa có transport nghiệp vụ tự refresh token.
- Backend có controller cho danh mục/giá, tổ chức/tuyến/lượt ghé, đối tác, đơn SI/SO, công nợ, đối soát, tồn/sell-out/kế hoạch. Quản trị mới có endpoint gán vai. Chưa có controller ticket/task/notification/attachment và API danh sách người dùng/cấu hình/audit.
- CORS chưa cho phép `Idempotency-Key`, khiến request tạo đơn khác origin bị chặn ở preflight.
- Workspace có sẵn nhiều sửa đổi và file chưa được Git theo dõi. Không reset, không stage hay commit gộp các thay đổi đang dở của người dùng.

## Đã triển khai trong lượt này

| Thành phần | Thay đổi |
| --- | --- |
| `packages/api/client.ts` | Một transport `/api/v1`; base URL từ env; access token trong bộ nhớ; refresh xoay vòng dùng một promise chung; replay tối đa một lần; giữ body và idempotency key; giữ AbortError; lỗi tiếng Việt; bỏ phiên khi refresh bị từ chối; chặn phản hồi của phiên cũ sau logout. |
| `packages/api/query.ts` | TanStack Query; cache 30 giây, làm mới khi focus, truyền AbortSignal vào query; không retry lỗi quyền; clear cache khi đổi phiên. |
| `packages/api/services.ts` | Service phân trang/chi tiết/ghi dữ liệu; service tạo SI/SO và tính giá trên server. Các chữ ký auth cũ vẫn được export từ vị trí cũ. |
| `apps/live` | Workspace dùng chung cho bốn cổng, menu/nút theo `/auth/me`; chọn cổng theo tenant; màn hình tải/rỗng/lỗi/thử lại; phân trang server; lọc chỉ ở endpoint hỗ trợ; bảng chi tiết và biểu mẫu theo Java request records. |
| Luồng đơn | Xem danh sách/chi tiết/lịch sử và bốn lượng riêng; tạo SI/SO có khóa idempotency; gửi duyệt/tiếp nhận/duyệt/từ chối/hủy/xuất/nhận/đổi trả theo quyền. Backend quyết định trạng thái, giá và kiểm hạn mức. Duyệt/xác nhận có chỉ dấu đang chờ, rollback cache khi lỗi, invalidate sau mutation; không tự dự đoán trạng thái nghiệp vụ. |
| Danh mục | Danh sách/chi tiết; biểu mẫu tạo sản phẩm và quy đổi, bảng giá, khuyến mãi, voucher; workflow gửi/duyệt/từ chối/kích hoạt. Ngày kích hoạt chuyển thành UTC. |
| Vận hành | Danh sách tổ chức/tuyến/MCP/lượt ghé/NPP/điểm bán; tạo đơn vị/tuyến/điểm bán; check-in/out lấy GPS thật từ trình duyệt; không tự chấp thuận ngoại lệ GPS. |
| Tồn/kế hoạch/công nợ | Danh sách; cập nhật tồn, khai sell-out, kế hoạch, chỉ tiêu, hạn mức, thanh toán, khấu trừ, tạo/chốt kỳ và xác nhận đối soát theo API hiện có. Import CSV/XLSX có preview phân trang, lỗi từng dòng và bước commit riêng. |
| Mobile PWA | Đơn chờ gửi trong localStorage theo tenant:user; key sinh trước và giữ khi thử lại; retry khi online và định kỳ trong khi cổng Sales mở; khóa đồng bộ giữa các tab khi trình duyệt hỗ trợ Web Locks; giá thay đổi hoặc chưa có giá thì cần xác nhận; không tự retry lỗi quyền; giữ dữ liệu hỏng để khôi phục thay vì ghi đè. |
| Demo/production | Chỉ lazy-load demo khi `DEV && VITE_USE_MOCK === 'true'`. Vite chặn build nếu graph có mock-data. `check:p4` kiểm tra mã live và JS build không chứa import mock/tên demo. Role switcher chỉ tồn tại trong demo. |
| Backend/PWA | Cho phép header `Idempotency-Key` trong CORS và thêm regression test. Service worker bỏ qua `/api/` và request có Authorization; giới hạn cache vào shell/static assets. |

Workspace API là giao diện mới dùng chung; các layout/demo cũ không bị sửa. Đây **chưa phải** chuyển toàn bộ hành vi và bố cục của các màn demo sang API theo tỷ lệ 1:1. Không suy ra rằng mọi màn trong demo đã có tương đương hoàn chỉnh ở chế độ thật.

## Kiểm tra đã chạy

| Lệnh | Kết quả |
| --- | --- |
| `npm test` | 13 test qua: auth/validation, refresh đồng thời, replay giữ key/body, 403, refresh hết hạn, cancellation, logout khi refresh đang chạy, queue/giá thay đổi/retry/cô lập tài khoản. |
| `npm run build` | TypeScript và Vite production build qua; graph không có mock-data. |
| `npm run check:architecture` | Qua. |
| `npm run check:p4` | Không có mock-data, Tạp hóa Lan, SI-1182, SO-24081 trong mã live và JS production. |
| `npm run test:p4` | Bốn cổng qua: guard/login, redirect theo tenant, menu theo quyền, phân trang server, bearer token, 403/thử lại, responsive và logout. Thêm kiểm tra payload version/reason và hiển thị lỗi 409 khi duyệt SI. Dùng phản hồi API kiểm thử, không dùng DB thật. |
| `mvn -f backend/pom.xml verify` | 58 test qua, 0 lỗi, 0 bỏ qua; đóng gói JAR thành công. Có test mới kiểm CORS cho header idempotency và từ chối origin ngoài cấu hình. |

Đã xem ảnh kiểm tra desktop NSX và mobile Sales, không có tràn ngang toàn trang; bảng trên mobile cuộn ngang trong khung. Ảnh kiểm tra nằm trong `backend/target/p4-ui/` (không đưa vào Git).

Tìm toàn repo vẫn thấy năm file import mock trong các màn demo: `apps/web-nsx/Operations.tsx`, `apps/web-nsx/WorkspaceScreens.tsx`, `apps/portal-npp/PortalNpp.tsx`, `apps/portal-outlet/PortalOutlet.tsx`, `apps/mobile-sales/MobileSales.tsx`. Những file này được giữ để chạy demo và bị loại khỏi graph production; tiêu chí không phải xóa mọi chữ mock khỏi tài liệu/test/demo.

## Việc còn lại để nghiệm thu P4

1. Chuyển nốt các tương tác của màn demo: dashboard/chỉ số tổng hợp, báo cáo hai lớp, sửa đơn nháp/đặt lại, quản lý tuyến và sửa MCP, checklist, chuyển người ghé thay, duyệt GPS, sửa/liên hệ/gán NPP/duyệt điểm bán, xử lý ngoại lệ đối soát và phiếu điều chỉnh. Backend đã có nhiều API cho nhóm này nhưng workspace live chưa phủ hết UI.
2. Bổ sung hợp đồng API còn thiếu của P3 cho ticket/task/notification/attachment, danh sách người dùng/cấu hình/audit, lựa chọn NSX phục vụ NPP và lựa chọn NPP phục vụ điểm bán. Hiện một số biểu mẫu quản trị/tạo đơn còn cần mã định danh do quản trị viên cung cấp; chưa thể coi là trải nghiệm hoàn chỉnh cho người dùng cuối.
3. Hoàn thiện upload ảnh thực nhận/đổi trả. Hiện UI chỉ nhận attachment ID có sẵn, không giả vờ upload thành công; endpoint attachment chưa tồn tại. Luồng nhận thiếu/hỏng có ảnh chưa nghiệm thu.
4. PWA chưa có kho danh mục/giá cache bền vững cho trường hợp đóng rồi mở lại hoàn toàn offline. Đơn đã lưu tồn tại sau reload, nhưng cần đăng nhập và có dữ liệu sản phẩm để tạo đơn mới. Nếu cần app Expo, phải có dự án native riêng và dùng SecureStore; workspace hiện không có dự án Expo.
5. Đối chiếu UI với các vai thật trên staging, xác nhận tenant/scope A/R/T/O ở API và chạy luồng SI/SO thật. Test trình duyệt hiện kiểm wiring bằng fixtures; không thay thế kiểm thử tích hợp frontend → backend → DB.
6. Kiểm tra ngoại lệ quota bộ nhớ, thu hồi phiên phía server khi logout mất mạng, và các trường hợp reload/offline trên thiết bị mục tiêu. Với auth JSON hiện tại, reload web yêu cầu login lại; không lưu refresh token vào localStorage để tránh làm yếu bảo mật.
7. Chốt P4 sau khi các mục trên qua nghiệm thu rồi tạo commit giai đoạn riêng. Lượt này chưa tạo commit hoàn tất P4 và không đưa các sửa đổi P3/ứng dụng đang dở có sẵn vào commit của mình.

## Cấu hình/chạy

`VITE_API_URL` ưu tiên hơn `VITE_API_BASE_URL`; hỗ trợ URL host hoặc URL kết thúc `/api/v1`. Mặc định `http://127.0.0.1:8080`. Không có biến cấu hình nào chứa secret được thêm vào repo. `.env.example` chỉ liệt kê tên biến.

```powershell
corepack pnpm install --frozen-lockfile
npm run dev
# .env.local: VITE_API_URL=<dev/staging API>, VITE_USE_MOCK=false

npm test
npm run build
npm run check:architecture
npm run check:p4
npm run test:p4
mvn -f backend/pom.xml verify
```

Dependency hiện có trong workspace dùng cấu trúc pnpm. `npm install` vào cây junction này báo lỗi metadata; đã dùng `corepack pnpm add @tanstack/react-query@5` và thêm `pnpm-lock.yaml`. Pnpm báo bỏ qua install script esbuild; binary hiện có vẫn chạy build thành công. Cài mới cần tuân theo chính sách dependency/build script của môi trường đích.
