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

## Railway API + PostgreSQL

Backend và database đều chạy trong cùng Railway project. API dùng private network để kết nối PostgreSQL; không tạo public domain cho database.

1. Tạo Railway project và thêm service **PostgreSQL**. Chọn region **Southeast Asia (Singapore)** khi tạo database; giữ database ở private network, không tạo public domain.
2. Thêm service API bằng **Deploy from GitHub repo**, chọn repository `synerasuz26-team/Synera_v02` và đặt root directory là `/backend`. Railway sẽ build JAR bằng `backend/Dockerfile`.
3. Chọn region **Southeast Asia (Singapore)** cho service API để cùng region với database.
4. Trong Railway → API service → **Variables**, tham chiếu biến từ service PostgreSQL. Nếu tên service không phải `Postgres`, thay phần `Postgres` trong các tham chiếu:

   ```text
   DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
   DB_USER=${{Postgres.PGUSER}}
   DB_PASSWORD=${{Postgres.PGPASSWORD}}
   DB_POOL_SIZE=5
   APP_CORS_ALLOWED_ORIGINS=https://<your-frontend-domain>
   SESSION_COOKIE_SECURE=true
   SESSION_COOKIE_SAME_SITE=none
   ```

   `DB_URL` dùng host/port private của Railway; không thêm `sslmode=require` trừ khi Railway yêu cầu cho kết nối đó.
5. Tạo domain public cho API trong **Settings → Networking → Generate Domain**. Railway cấp `PORT`; Spring Boot đã lắng nghe port này. Đặt health check path là `/actuator/health`.
6. Nếu frontend chạy trên Railway, triển khai frontend thành service riêng và đặt `VITE_API_BASE_URL=https://<your-api-domain>` trong build environment. Cập nhật `APP_CORS_ALLOWED_ORIGINS` thành đúng domain frontend.

Flyway tự tạo schema tài khoản và session khi API kết nối lần đầu. Không commit mật khẩu database hoặc tệp `.env`; đặt `DB_PASSWORD` bằng biến tham chiếu Railway, không nhập mật khẩu vào mã nguồn.

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
