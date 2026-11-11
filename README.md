# SYNERA — Landing, xác thực và API

Ứng dụng gồm trang landing, đăng nhập và đăng ký. API Java/Spring Boot lưu tài khoản trong PostgreSQL; mật khẩu được băm bằng PBKDF2 và phiên đăng nhập dùng cookie HttpOnly cùng CSRF token. Frontend không kết nối trực tiếp tới database.

## Chạy local

Cần Node.js 22+ và các dependency trong `package.json`:

```powershell
npm install
npm run dev
```

Mở `http://127.0.0.1:5173/`. Landing ở `/` hoặc `/#/landing`; đăng nhập ở `/#/login`; đăng ký ở `/#/register`.

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

## Railway API + Neon PostgreSQL

Railway chạy API; Neon cung cấp PostgreSQL. Không tạo thêm database Railway nếu dùng Neon.

1. Tạo project Neon ở Singapore và lấy thông tin kết nối pooled (`-pooler`) từ Neon.
2. Push mã nguồn lên GitHub rồi tạo Railway project bằng **Deploy from GitHub repo**. Với service API, đặt root directory là `/backend`; Railway sẽ build bằng Dockerfile trong thư mục đó.
3. Chọn region **Southeast Asia (Singapore)** cho API để gần Neon.
4. Thêm các biến này trong Railway → API service → **Variables**. Thay placeholder bằng thông tin Neon; `DB_PASSWORD` là secret:

   ```text
   DB_URL=jdbc:postgresql://<pooled-host-from-neon>:5432/<database>?sslmode=require
   DB_USER=<user-from-neon>
   DB_PASSWORD=<password-from-neon>
   DB_POOL_SIZE=5
   APP_CORS_ALLOWED_ORIGINS=https://<your-frontend-domain>
   SESSION_COOKIE_SECURE=true
   SESSION_COOKIE_SAME_SITE=none
   ```

5. Tạo domain public cho API trong **Settings → Networking → Generate Domain**. Railway cấp `PORT`; Spring Boot đã lắng nghe port này. Đặt health check path là `/actuator/health`.
6. Đặt `VITE_API_BASE_URL=https://<your-api-domain>` trong build environment của dịch vụ frontend.

Không commit mật khẩu database hoặc tệp `.env`. Không gửi `DB_PASSWORD` vào chat; nhập trực tiếp trong Railway Variables. Cấu hình trong repository không tự tạo GitHub repo, Neon project hoặc Railway service.

## Kiểm tra

```powershell
npm run check:architecture
npm run format:check
npm test
npm run build
```

Kiểm tra backend bằng:

```powershell
mvn -f backend/pom.xml verify
```

Với kiểm thử trình duyệt, chạy production preview bằng `npm run preview -- --host 127.0.0.1`, sau đó chạy `npm run test:e2e` và `npm run test:design`. Có thể đặt `TEST_URL` nếu preview chạy khác cổng 4173.
