# SYNERA — Điều hành phân phối, landing, xác thực và API

Ứng dụng có Web NSX tại `/#/nsx`, Portal NPP tại `/#/npp`, Cổng điểm bán tại `/#/outlet`, Mobile Sales tại `/#/sales`, landing tại `/#/landing` và đăng nhập tại `/#/login`. Mặc định các giao diện dùng workspace API trong `apps/live`, client dùng chung tại `packages/api`, quyền lấy từ `/api/v1/auth/me`. Các màn hình demo cũ được giữ nguyên, chỉ bật trong development bằng `VITE_USE_MOCK=true`; production loại chúng khỏi dependency graph. Xem [báo cáo tiến độ P4](docs/P4_STATUS.md) để biết phạm vi đã nối và phần còn thiếu.

Backend Spring Boot dùng PostgreSQL, BCrypt cho mật khẩu mới và hỗ trợ nâng cấp mật khẩu PBKDF2 cũ. Auth hiện dùng access/refresh token, không dùng cookie phiên HttpOnly. Frontend giữ token trong bộ nhớ: tải lại trang cần đăng nhập lại. Mobile Sales hiện là PWA, không phải ứng dụng Expo; token không được ghi vào localStorage/sessionStorage. Chỉ đơn chờ gửi được lưu trên thiết bị, phân biệt theo tenant và tài khoản.

## Chạy local

Cần Node.js 22+ và các dependency trong `package.json`:

```powershell
corepack pnpm install --frozen-lockfile
npm run dev
```

Mở `http://127.0.0.1:5173/`, đăng nhập bằng tài khoản đã được cấp. Sau đăng nhập, ứng dụng chọn giao diện theo tenant/quyền; menu và nút dựa trên quyền máy chủ. Đăng ký công khai đã tắt. Không có bộ chuyển vai ở chế độ thật.

Trong `.env.local`, đặt `VITE_API_URL=http://127.0.0.1:8080` và `VITE_USE_MOCK=false`. `VITE_API_BASE_URL` vẫn được hỗ trợ để tương thích cấu hình cũ; `VITE_API_URL` được ưu tiên. Để xem demo cũ, đặt `VITE_USE_MOCK=true` rồi khởi động lại dev server. Cờ này không bật demo trong production.

## Chạy API local

Cần Java 25, Maven 3.9+ và PostgreSQL. Tạo database `synera`, sau đó chạy API:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/synera"
$env:DB_USER = "synera"
$env:DB_PASSWORD = "<your-local-password>"
$env:APP_CORS_ALLOWED_ORIGINS = "http://127.0.0.1:5173,http://localhost:5173"
mvn -f backend/pom.xml spring-boot:run
```

Flyway tạo schema khi API khởi động. Chạy frontend bằng `npm run dev`; có thể đặt `VITE_API_BASE_URL` trong `.env.local` nếu API không chạy tại `http://127.0.0.1:8080`.

## Deploy dev trên Railway (PostgreSQL + API + frontend)

Tạo một Railway project dev tách biệt; không tái sử dụng hoặc đổi biến của project production. PostgreSQL phải ở cùng project/region với API và chỉ API kết nối nó qua private networking. Không tạo public domain cho database. Mẫu biến không nhạy cảm nằm trong `deploy/railway-dev.env.example`; nhập secret trực tiếp trong Railway Variables, không commit `.env` hay giá trị secret.

1. Tạo project mới tên `synera-dev`, chọn cùng region cho cả ba service, rồi thêm service PostgreSQL. Dùng DB mới/rỗng; không gắn volume hay DB production.
2. Thêm API từ GitHub repo `synerasuz26-team/Synera_v02`, đặt root directory `/backend` để dùng `backend/Dockerfile`. Đặt biến API từ mẫu; nếu tên PostgreSQL service khác `Postgres`, sửa tên trong các biến tham chiếu. Sinh `JWT_SECRET` riêng (ít nhất 32 byte). Đặt `SEED_DEMO=true` và tự đặt `SEED_DEMO_PASSWORD` bí mật để có dữ liệu/tài khoản demo chỉ trong DB dev. Không bật demo trong production.
3. Tạo public domain cho API ở **Settings → Networking → Generate Domain**. Đặt health check path `/health` (chỉ `SELECT 1`). Đặt `CORS_ORIGINS` thành origin HTTPS chính xác của frontend sau khi frontend có domain.
4. Thêm service frontend từ cùng repo, root directory `/`, Dockerfile `deploy/frontend.Dockerfile`. Đặt build variable `VITE_API_URL=https://<api-domain>` và `VITE_USE_MOCK=false`; runtime variables `API_ORIGIN=https://<api-domain>` và `STORAGE_ORIGIN=https://<api-domain>` (ảnh đang tắt). Sinh frontend domain rồi cập nhật API `CORS_ORIGINS` thành origin frontend chính xác.
5. Đặt frontend health check path `/health`. Xác nhận cả ba service cùng project dev; sau khi deploy kiểm tra frontend login, API `/health`, CORS và `/api/v1/admin/storage` bằng SYS_ADMIN. PostgreSQL migrations chạy khi API khởi động; không chạy `reset:dev`, `flyway clean`, `DROP`, `TRUNCATE` hay seed ghi đè.

Frontend được host trên Railway theo lựa chọn hiện tại, nên project có ba service và sẽ tiêu thụ credit của Railway. Dashboard trước đó hiển thị Limited Trial với 28 ngày hoặc $4.53 còn lại; kiểm tra Usage trước khi tiếp tục sử dụng. Nếu Railway không chấp nhận biến `VITE_*` như build variables, cấu hình chúng trong service build settings để Docker build nhận được các build args.

## Kiểm tra

P5 có mục **Nhập dữ liệu nền** cho NSX_ADMIN và các vai NPP theo phạm vi, gồm tải mẫu XLSX, xem trước lỗi/cảnh báo, xác nhận ghi và báo cáo sau import. Hỗ trợ Excel/CSV theo cấu trúc workbook demo Vinamilk; không tự nạp demo vào tenant thật. Xem [hướng dẫn và báo cáo P5](docs/P5_STATUS.md) cho thứ tự nhập, phân quyền, endpoint và lệnh dọn demo có bảo vệ.

```powershell
npm run check:architecture
npm run format:check
npm test
npm run build
npm run check:p4
npm run test:p4
```

Kiểm tra backend bằng:

```powershell
mvn -f backend/pom.xml verify
```

`test:p4` tự chạy production preview tại cổng 4174 rồi đóng khi xong. Test dùng Edge headless và phản hồi API kiểm thử; không gọi Railway. Có thể đặt `BROWSER_CHANNEL` nếu cần dùng browser channel khác. Hai script cũ `test:e2e` và `test:design` vẫn kiểm tra các giả định giao diện demo/landing cũ, chưa phải bộ nghiệm thu P4; không dùng chúng để kết luận luồng dữ liệu thật đã hoàn thành.
## P6 — Job nền, tệp và thông báo

P6 bổ sung scheduler có khóa PostgreSQL và log lượt chạy, nhắc việc, ảnh S3 private qua presigned URL, hộp thông báo polling và hàng đợi Expo push. Xem [hướng dẫn P6](docs/P6_STATUS.md) về cấu hình storage/CORS, ngưỡng trong DB và các bước tích hợp app Expo native.

## P7 — Kiểm thử nghiệm thu

Chạy `npm run test:p7` để tự dựng DB test riêng, chạy các luồng Playwright/API thật có đối chiếu DB, kiểm ma trận RBAC từ workbook gốc và benchmark 50.000 đơn/5.000 điểm bán; runner tự dọn container. Xem [báo cáo và hướng dẫn P7](docs/P7_STATUS.md).

## P8 — Dung lượng, backup và cutover

Giới hạn cấu hình Railway và dung lượng volume phải được xác minh trong dashboard trước deploy; không coi cấu hình ví dụ là bằng chứng gói Free đang cấp đúng tài nguyên. Ảnh tắt mặc định (`FEATURE_PHOTOS=false`); không lưu ảnh vào database/volume. Chưa cấu hình kho lưu trữ ngoài thì giữ cờ này tắt. Audit log cũ không được xóa nếu chưa có nơi lưu trữ archive gzip ngoài database.

### Local database

Cần Docker Desktop đang chạy. PostgreSQL local mặc định là 16; xác nhận major version của database nguồn trước khi dùng nó để diễn tập restore/migration:

```powershell
docker compose up -d db
$env:DB_URL = "jdbc:postgresql://127.0.0.1:5432/synera"
$env:DB_USER = "synera"
$env:DB_PASSWORD = "synera_local_only"
$env:AUTH_JWT_SECRET = "<local-only-secret-32-bytes-or-more>"
$env:APP_ENV = "dev"
$env:DB_ENVIRONMENT = "dev"
./backend/scripts/db.ps1 -Task migrate
./backend/scripts/db.ps1 -Task seed:system
# Chỉ khi cần demo và đây là local/dev:
$env:SEED_DEMO = "true"
$env:SEED_DEMO_PASSWORD = "<local-demo-password>"
./backend/scripts/db.ps1 -Task seed:demo
docker compose up --build -d backend
npm test
npm run test:p7
```

Flyway chạy tự động khi backend khởi động. Không dùng `reset:dev` trên Railway; không chạy `flyway clean`, `DROP`, `TRUNCATE` hay xóa volume để khôi phục.

### Backup và restore

Backup chạy từ máy local qua hostname/port public của PostgreSQL; mật khẩu chỉ lấy từ biến môi trường local. Thư mục backup mặc định là `~/synera-backups` ngoài repository. Đặt `PG_TOOLS_IMAGE` trùng major version của server:

```powershell
$env:PGHOST = "<public-database-host>"
$env:PGPORT = "<public-database-port>"
$env:PGDATABASE = "<database-name>"
$env:PGUSER = "<database-user>"
$env:PGPASSWORD = "<database-password>"
$env:PG_TOOLS_IMAGE = "postgres:<server-major>-alpine"
npm run db:backup
```

Để diễn tập backup trên PostgreSQL Compose local, đặt `PGHOST=db`, `PGPORT=5432`, `PGDATABASE=synera`, `PGUSER=synera`, mật khẩu local và `PG_DOCKER_NETWORK=synera_web_default`. Cấu hình này cho phép container `pg_dump` tạm thời kết nối DB mà không mở thêm cổng host.

Để diễn tập restore vào database mới trên PostgreSQL local, không ghi đè database hiện có:

```powershell
docker compose up -d db
$env:RESTORE_DATABASE = "synera_restore_predeploy"
npm run db:restore -- "<absolute-path-to-backup.sql.gz>"
npm run db:verify -- synera_restore_predeploy
```

Lưu backup tuần tự bên ngoài repo; bắt buộc backup trước mỗi lần deploy có migration, restore vào local, migrate database restore, rồi chạy test trước deploy. Chỉ giữ bản backup sau khi xác nhận file gzip và so sánh row-count manifest.

### Deploy, dung lượng và rollback

Trước deploy: kiểm tra Railway volume usage/plan/Usage trong dashboard; nếu volume vượt 70% thì dừng migration và xử lý trước. `/api/v1/admin/storage` (SYS_ADMIN) hiển thị database size theo bảng cùng ước tính so với 500 MB; PostgreSQL không tính WAL và các tệp volume khác trong số liệu này, nên dashboard Railway mới là nguồn xác nhận dung lượng volume. Mức 70%/85% từ API là cảnh báo ước tính, không phải phép đo volume chính xác. Không xóa audit hoặc giao dịch để giải phóng chỗ khi chưa có archive/chấp thuận phù hợp.

Sau khi deploy, xác nhận `/health`, CORS với domain frontend, đăng nhập, API thật và frontend production build. Chỉ một replica; theo dõi RAM và Usage trong Railway sau khi chạy thực tế. Railway Free, sleeping, volume, credit và chi phí cần được kiểm tra trên project cụ thể; repo không thể xác nhận các số liệu dashboard.

Rollback ứng dụng bằng cách redeploy image/deployment ứng dụng trước đó nếu schema vẫn tương thích. Migration chỉ tiến; không rollback bằng cách xóa/đổi schema. Nếu bản schema mới không tương thích bản ứng dụng cũ, dừng rollback và triển khai bản sửa forward sau khi kiểm thử trên restore local.
