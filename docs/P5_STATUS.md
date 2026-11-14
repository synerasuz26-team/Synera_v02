# P5 — Nhập dữ liệu nền

Ngày triển khai: 13/11/2026. Nguồn được hỗ trợ: Excel `.xlsx` và CSV UTF-8. Workbook Vinamilk do người dùng cung cấp là **demo và mẫu cấu trúc**, không được coi là dữ liệu kinh doanh thật.

## Sử dụng

1. Chạy migration, đăng nhập bằng tài khoản đã được cấp trong tenant đích. Trong Web NSX hoặc Portal NPP, chọn **Nhập dữ liệu nền**.
2. Chọn loại dữ liệu, tải mẫu `.xlsx`, thay dòng ví dụ bằng dữ liệu thật và giữ nguyên tiêu đề dòng 1.
3. Tải lên kèm lý do. Hệ thống lưu lô xem trước, chuẩn hóa dữ liệu, báo lỗi/cảnh báo từng dòng; chưa ghi dữ liệu nghiệp vụ.
4. Sửa tệp nếu có lỗi rồi tải lên lại. Chính sách hiện tại là **toàn bộ hoặc không ghi**; không tự bỏ qua dòng lỗi.
5. Khi không còn lỗi, xem cảnh báo trùng/đối chiếu và xác nhận ghi. Backend kiểm lại quyền, tenant, tham chiếu và phiên bản trước khi ghi trong một giao dịch SERIALIZABLE. Bất kỳ lỗi giữa lô nào cũng rollback cả lô.
6. Xem báo cáo số dòng nhận/lỗi/cảnh báo và số tạo mới/cập nhật/không thay đổi. Có lịch sử lô theo tài khoản, phân trang và lọc chỉ dòng lỗi. Số bản ghi thành công tính theo đối tượng chính của dòng nguồn; quy đổi/liên kết được ghi cùng giao dịch.

Các mẫu được phục vụ bởi backend tại `src/main/resources/import-templates/`. Mẫu đóng gói không chứa mật khẩu mặc định và không dùng giá/thương hiệu giả làm dữ liệu thật.

## Mẫu và ánh xạ dữ liệu

| Loại | File mẫu | Sheet | Khóa nghiệp vụ |
| --- | --- | --- | --- |
| SKU + đơn vị cơ sở/thùng | `products.xlsx` | `products` | `sku_code` |
| NPP | `npp.xlsx` | `npp` | `npp_code` |
| Điểm bán + liên hệ + NPP phục vụ | `outlets.xlsx` | `outlets`, `outlet_npp` tùy chọn | `outlet_code`; cặp outlet/NPP |
| Cây tổ chức | `org_units.xlsx` | `org_units` | `org_code` |
| Tuyến, điểm bán trên tuyến và MCP | `routes.xlsx` | `routes`, `route_outlets` tùy chọn | `route_code`; cặp route/outlet |
| Người dùng + vai | `users.xlsx` | `users` | `username`, email duy nhất |
| Tồn đầu kỳ | `inventory.xlsx` | `inventory_npp` | NPP + SKU; một snapshot đầu kỳ ổn định |
| Hạn mức + công nợ đầu kỳ | `credit.xlsx` | `npp` | NPP trong tenant NSX |

Tên sheet và các cột gốc lấy từ workbook được cung cấp, gồm:

- `products`: `sku_code, name, category, base_unit, case_unit, units_per_case, note`.
- `npp`: `npp_code, name, org_unit_code, address, credit_limit_vnd, debt_current_vnd, credit_remaining_vnd`.
- `outlets`: `outlet_code, name, outlet_type, address, contact_name, org_unit_code, route_code, status`.
- `org_units`: `org_code, parent_code, name, level, path`.
- `routes`: `route_code, name, org_unit_code, sales_username, supervisor_username`.
- `route_outlets`: `route_code, outlet_code, visit_order, mcp_weekday, mcp_weeks`.
- `users`: `username, full_name, role_code, tenant_code, org_unit_code, route_code, password`.
- `inventory_npp`: `npp_code, sku_code, stock_case, sellout_7d_case, days_cover, stock_status, updated_at`.

Bổ sung có chủ đích vì nguồn chưa có: `contact_phone`, `contact_email`, `npp_code` tùy chọn trên `outlets`; `email` trên `users` để đăng nhập với hợp đồng auth hiện có. Nếu username đã là email thì không cần điền thêm email. `outlet_npp` vẫn giữ đúng hai cột `outlet_code, npp_code` của nguồn.

Sheet `doi_chieu_demo_cu` chỉ dùng tham khảo đối chiếu, không được thực thi như chỉ dẫn hoặc nhập vào dữ liệu nghiệp vụ. Các sheet giá/khuyến mãi/đơn/ticket trong workbook nằm ngoài phạm vi import dữ liệu nền P5 này; hệ thống chỉ đọc các sheet thuộc loại được chọn.

Thứ tự khuyến nghị: tổ chức → SKU → NPP → tuyến chưa gán người → điểm bán → người dùng → nhập lại tuyến để gán người/MCP → tồn đầu kỳ → hạn mức/công nợ. Nếu dữ liệu tham chiếu đã tồn tại thì có thể nhập riêng loại tương ứng. Đơn vị cha trong cùng tệp có thể đứng sau đơn vị con; hệ thống sắp xếp theo cây và từ chối chu trình.

CSV chỉ chứa sheet chính của loại đã chọn; để nhập các sheet liên kết, dùng XLSX. Tối đa 5 MB và 5.000 dòng/lô; lô người dùng giới hạn 200 dòng vì phải băm mật khẩu.

## Quyền và tính toàn vẹn

Permission `foundation:import` được thêm bằng V11 và seed hệ thống, có cờ nhạy cảm. Backend tải lại danh tính/quyền từ DB, không chỉ dựa vào menu hay claim cũ trong JWT.

| Vai | Loại import được phép |
| --- | --- |
| NSX_ADMIN | Cả tám loại, trong tenant NSX và các NPP có quan hệ hiệu lực |
| NPP_OWNER | Điểm bán/liên hệ, tồn của chính NPP, người dùng cùng tenant |
| NPP_STAFF | Điểm bán/liên hệ, tồn của chính NPP |
| Các vai khác | Từ chối |

- Không nhận tenant UUID từ frontend để đổi đích. Mã tham chiếu trong file đều được tra lại theo phạm vi. NPP không được gán tuyến/tổ chức của NSX hoặc ghi tồn NPP khác.
- Import người dùng vẫn tuân theo tách nhiệm vụ: không sửa/gán vai cho chính mình, không gán vai khác loại tenant hoặc cao hơn cấp người nhập, không sửa tài khoản cấp cao hơn. NSX_ADMIN hiện có cấp 20 nên không thể dùng import để tạo vai quản lý cấp 40–100. Người dùng phải có quyền `role:assign`.
- Mật khẩu mới băm ngay trong preview, không lưu file gốc, không trả password/hash cho frontend và không đưa vào audit. Sau commit, hash trung gian được bỏ khỏi lô. Tài khoản cũ giữ mật khẩu; import chỉ bổ sung vai được phép và không xóa các vai hiện có.
- Dữ liệu được trim/collapse khoảng trắng; mã viết hoa, bỏ dấu/khoảng trắng; điện thoại VN chuẩn thành `+84`. Ô điện thoại phải là văn bản để giữ số 0 đầu. Email chuẩn chữ thường.
- Cảnh báo trùng điểm bán xét cùng tên đã bỏ dấu và địa chỉ gần giống, trong phạm vi được phép và giữa các dòng cùng tệp. Chỉ cảnh báo, không tự gộp/xóa.
- SKU không đổi đơn vị cơ sở hoặc hệ số của đơn vị đã tồn tại qua import. `stock_case` được đổi sang số lượng cơ sở ở backend; các cột bán ra 7 ngày/độ phủ/trạng thái chỉ đối chiếu, không tự sinh doanh số.
- Điểm bán mới và liên kết NPP mới ở trạng thái chờ duyệt, không tự trở thành điểm bán giao dịch hiệu lực.
- Nhập loại NPP không ghi số tài chính. Loại CREDIT tạo hạn mức `DRAFT` và một hóa đơn số dư đầu kỳ ổn định. `credit_remaining_vnd`, nếu điền, phải khớp hạn mức trừ công nợ. Không sửa số đầu kỳ đã được dùng/thanh toán hoặc hạn mức đã gửi/duyệt; kỳ đã chốt được bảo vệ.
- NSX_ADMIN có thể chọn **Gửi hạn mức nhập này chờ duyệt** từ báo cáo lô CREDIT. Thao tác chỉ áp dụng các hạn mức do chính người đó tạo trong lô đã commit; không cấp thêm quyền quản lý/duyệt tài chính. Vai có quyền `credit_limit:approve` khác người tạo duyệt qua workflow hiện có.
- Bản xem trước cũ bị từ chối nếu phiên bản dữ liệu đã đổi. Nhập lại cùng dữ liệu dùng identity theo mã nghiệp vụ và payload hash; không thêm bản ghi hoặc snapshot trùng. Gọi lại commit cùng batch trả lại báo cáo cũ.
- MCP đã tồn tại khác lịch trong file không bị ghi đè lịch sử; điều chỉnh lịch qua nghiệp vụ MCP. Import này phục vụ thiết lập dữ liệu nền.

## Demo và tenant thật

- `tenant.is_demo` là cờ phân loại, dữ liệu con kế thừa phân loại qua tenant. Tenant NPP/điểm bán do import tạo kế thừa cờ của người nhập.
- Migration chỉ đánh dấu demo cũ bằng **cặp UUID xác định từ seed + code khớp**, không đoán theo tên hiển thị. Các tenant khác mặc định `false`.
- Tệp có `is_demo=1/true` trong sheet `tenants` hoặc dòng dữ liệu bị chặn nếu nhập vào tenant thật. Không tự phân loại theo tên thương hiệu.
- Seed demo kiểm tra APP_ENV/DB_ENVIRONMENT/DB_URL tại cả CLI và service, từ chối production/môi trường lệch nhau. Trùng mã tenant hoặc email với tài khoản thật thì dừng, không ghi đè.
- Bộ seed demo legacy đã có trước P5 vẫn được giữ để tránh viết lại P1 ngoài phạm vi. Workbook Vinamilk được dùng làm hợp đồng mẫu và nguồn demo khi chủ động nhập vào tenant demo; không tự đổi toàn bộ seed giao dịch cũ sang Vinamilk trong lượt này.

Lệnh bảo trì, **chưa chạy trên Railway**:

```powershell
# Đặt DB_URL/DB_USER/DB_PASSWORD bằng biến môi trường của đúng dev/staging.
$env:APP_ENV = 'dev'
$env:DB_ENVIRONMENT = 'dev'
powershell -ExecutionPolicy Bypass -File backend/scripts/db.ps1 -Task 'purge:demo:plan'

# Sau khi xem kế hoạch và chủ động quyết định xóa:
$env:PURGE_DEMO_CONFIRM = 'DELETE_DEMO_ONLY'
powershell -ExecutionPolicy Bypass -File backend/scripts/db.ps1 -Task 'purge:demo'
```

Purge chỉ xét tenant đã gắn `is_demo`, từ chối nếu có FK/phạm vi đa hình chéo với dữ liệu thật, xử lý phụ thuộc trong một giao dịch và giữ biên nhận tại `demo_cleanup_log`. Không DROP/TRUNCATE/reset schema, không xóa role/permission dùng chung. Đã kiểm thử cả từ chối tham chiếu chéo và xóa demo giữ nguyên tenant/sản phẩm thật trên PostgreSQL tạm.

## Endpoint đã bổ sung

| Method | Endpoint | Chức năng |
| --- | --- | --- |
| GET | `/api/v1/foundation-imports/types` | Các loại được phép và cờ demo của tenant |
| GET | `/api/v1/foundation-imports/{kind}/template` | Tải XLSX có ví dụ |
| POST multipart | `/api/v1/foundation-imports/{kind}/preview` | File + reason; kiểm tra và chuẩn hóa |
| GET | `/api/v1/foundation-imports/batches` | Lịch sử riêng, page/size |
| GET | `/api/v1/foundation-imports/batches/{id}` | Báo cáo lô |
| GET | `/api/v1/foundation-imports/batches/{id}/rows` | Dòng preview, page/size/errorsOnly |
| POST | `/api/v1/foundation-imports/batches/{id}/commit` | Ghi cả lô hoặc rollback |
| POST | `/api/v1/foundation-imports/batches/{id}/submit-credit` | Gửi hạn mức nháp của lô chờ người khác duyệt |

Schema sinh từ controller/record tại `/api/v1/openapi`; đã kiểm tra endpoint OpenAPI trên backend local.

## Kiểm tra và giới hạn nghiệm thu

- Kết quả cuối: `mvn verify` **81 test, không lỗi/skip**, gồm 14 test PostgreSQL thực; `npm test` **14 test đạt**. Build mặc định, `check:architecture`, `check:p4` và E2E P4 cho cả bốn vai đều đạt. E2E P5 đã chạy với API/PostgreSQL local thật cho NSX và NPP, gồm tải mẫu, lỗi từng dòng, chưa ghi trước xác nhận, chạy lại/cập nhật, 403 và phạm vi tenant; đã xem lại giao diện desktop/mobile.
- CLI `purge:demo:plan` đã chạy thành công trên DB kiểm thử; chỉ mở kết nối JDBC để đọc kế hoạch, không khởi động Flyway, web hay seed. Bootstrap cũng có kiểm thử không tự seed khi chạy tác vụ CLI và chặn cờ demo trên production trước mọi seed.
- Migration V1–V11 đã chạy từ DB trống trên PostgreSQL 16 trong Docker local, không dùng DB_URL của Railway. Các lần tiếp theo validate schema và không chạy lại migration.
- Bộ tích hợp kiểm: preview chưa ghi, rollback khi dòng thứ hai lỗi DB, nhập lại không trùng, cập nhật và khóa phiên bản, tenant spoofing/đọc lô người khác, vai người dùng, bảo mật password, thiếu tham chiếu/chu trình/sai kiểu, chuẩn hóa và cảnh báo điểm bán, tạo NPP/tuyến/MCP, quy đổi tồn và khóa kỳ theo múi giờ VN, công nợ/hạn mức nháp và gửi chờ duyệt, cleanup demo, auth refresh timestamp và đọc SKU qua service danh mục hiện có.
- Kiểm tra tất cả mẫu bằng parser backend, tên sheet/cột, dòng ví dụ, số điện thoại giữ số 0 và ngày Excel/ISO; đã render và rà bố cục các mẫu.
- Các lỗi cũ phát hiện khi chạy API thật đã sửa: refresh token dùng `Instant` không được driver PostgreSQL tự suy luận kiểu; SQL danh sách/chi tiết sản phẩm bị nối dính `WHEREp`/`ANDp`. Đã thêm kiểm thử PostgreSQL hồi quy. Lỗi DB bất ngờ được trả JSON 500 thống nhất, không giả thành lỗi phiên đăng nhập.
- Bộ test trình duyệt `npm run test:p5` dùng backend và PostgreSQL kiểm thử thật, không stub API. Cần fixture và API local riêng; script chỉ chấp nhận API `127.0.0.1`. Không trỏ script này vào staging/production.
- Tệp đính kèm hiện là demo, chưa có nguồn kinh doanh thật để nghiệm thu dữ liệu. Chưa migrate/import/purge Railway và chưa triển khai. Các phần P4 còn thiếu vẫn theo báo cáo P4; P5 không đánh dấu chúng hoàn thành.

Lệnh kiểm:

```powershell
npm test
npm run build
npm run check:architecture
npm run check:p4
npm run test:p4
mvn -f backend/pom.xml verify
# Test tích hợp chỉ bật khi P5_TEST_DB_URL là jdbc:postgresql://127.0.0.1:<port>/synera_p5
# và P5_TEST_DB_USER/P5_TEST_DB_PASSWORD được cấu hình cho DB kiểm thử riêng.
# npm run test:p5 cần P5_TEST_API_URL và P5_E2E_PASSWORD của fixture local;
# build frontend với VITE_API_URL trỏ đúng API kiểm thử trước khi chạy.
```

## Nhóm tệp thay đổi

- Backend: `importing/*`, V11, `import-templates/*.xlsx`, `DemoTenantCleanup`, các guard/runner demo, seed permission, handler lỗi; sửa timestamp auth và SQL sản phẩm tối thiểu để luồng thật chạy được.
- Frontend: `FoundationImports.tsx`, menu/workspace, tải Blob dùng chung trong API client, CSS checkbox.
- Kiểm thử: parser/mẫu, PostgreSQL P5, bảo vệ seed, test Blob, E2E thật; script tạo mẫu và đọc cấu trúc nguồn; tài liệu này.
- Working tree trước P5 đã có nhiều thay đổi P3/P4 chưa commit; không reset hoặc gộp tùy tiện các thay đổi đó vào một commit hoàn tất dự án.
