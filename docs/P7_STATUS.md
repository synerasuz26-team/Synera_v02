# P7 — Kiểm thử nghiệp vụ và hiệu năng

## Chạy lại

Yêu cầu: Docker Desktop đang chạy Linux containers, Java 25+, Maven, Node và dependency repo, Edge headless (hoặc đặt `BROWSER_CHANNEL`). Chạy từ repo:

```powershell
npm run test:p7
# Hoặc chỉ định Maven ở máy khác:
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/test-p7.ps1 -MavenPath '<maven>/bin/mvn.cmd'
```

Runner tự tạo PostgreSQL 16 và S3 emulator Moto riêng, cổng localhost ngẫu nhiên, mật khẩu/JWT secret sinh mới. DB P7 có tên `synera_p7`, P5/P6 có DB riêng trong cùng container tạm để chạy lại integration test. Không đọc DB_URL dev/production. Không seed hoặc gọi Railway.

DB dùng tmpfs, không gắn volume hay thư mục dữ liệu người dùng. Java harness kiểm URL, tên DB, run ID, Docker label, cổng và tmpfs trước khi kết nối. Java application chạy từ bản sao JAR riêng của lượt test. `finally` dừng process test, chỉ xóa các container có label đúng run ID; giữ báo cáo ở `backend/target/p7`. Không DROP/TRUNCATE/reset DB dev.

`-SkipPerformance` chỉ dùng chẩn đoán nhanh, không phải lượt nghiệm thu P7 đầy đủ. Maven/JVM có giới hạn bộ nhớ; các browser context được đóng giữa kịch bản. Job nền và operations monitor tự động tắt trong API test để tránh timer dọn dữ liệu/nhiễu phép đo; kịch bản a gọi đúng `DistributedJobRunner` và `BackgroundScheduler.nightly` từ harness test-only. Không thêm endpoint chạy job hay backdoor vào application production.

`-AcceptanceOnly` chạy lại fixture/E2E trên JAR đã build; runner từ chối khi mã backend hoặc migration mới hơn JAR. Lượt nghiệm thu sau thay đổi backend phải dùng lệnh đầy đủ ở trên. `progress.json` ghi kết quả từng kịch bản khi bộ benchmark còn chạy.

## Phạm vi E2E

Playwright dùng trình duyệt Edge và API thật, fixtures được ghi vào PostgreSQL. Những bước tương tác chính đi qua màn hình tạo đơn, duyệt, xuất kho, xác nhận nhận hàng và hàng đợi mobile. Một số thao tác hậu kiểm/đối soát/điều chỉnh/giao bù gọi REST bằng Playwright APIRequestContext rồi khẳng định kết quả trong DB. Không mock phản hồi API thành công. Kịch bản mạng yếu chỉ làm mất phản hồi **sau khi request đã được server xử lý và commit**.

Mobile hiện là web/PWA với viewport 390×844. Có kiểm thử local draft, xác nhận giá máy chủ, mất phản hồi và retry cùng Idempotency-Key. Không có app Expo/Detox trong repo để nghiệm thu native iOS/Android; đây là kiểm thử mobile web, không phải chứng nhận native hoặc push FCM/APNs.

Dữ liệu là fixture nghiệp vụ kiểm thử lưu trong DB thật, không phải dữ liệu khách hàng/production. Để mô phỏng ngày T+1 mà không thay đồng hồ máy, kịch bản a đưa `created_at` của đúng đơn fixture đã nhận về ngày T; các trạng thái, số lượng, hóa đơn, xác nhận hai bên và chốt ngày vẫn chạy bằng nghiệp vụ/API/job thật.

## Kịch bản và bằng chứng

Lượt `6132aac17518` ngày 14/11/2026 đã đạt cả sáu kịch bản a–f và benchmark (31 khẳng định trực tiếp trong DB và 1.744 cặp vai–quyền). Backend: 100 test, không lỗi và không bỏ qua. Kết quả build/frontend và dọn tài nguyên cuối lượt được ghi trong các tệp bằng chứng bên dưới.

| Kịch bản | Kết quả | Khẳng định DB |
| --- | --- | ---: |
| a — Sell-in, đối soát đêm, chốt ngày và chạy job lặp | PASS | 7 |
| b — Hạn mức và quyền duyệt vượt | PASS | 2 |
| c — Sell-out, thiếu hàng, ticket/việc và giao bù | PASS | 8 |
| d — Mất phản hồi, retry cùng Idempotency-Key | PASS | 1 |
| e — Bất biến kỳ và duyệt điều chỉnh khác người | PASS | 8 |
| f — Vai/quyền, tenant, tuyến, sửa ID/JWT | PASS | 1 |
| perf — 50.000 đơn, 5.000 điểm bán, độ trễ và N+1 | PASS | 4 |

| Mã | Kịch bản | Khẳng định chính trong DB |
| --- | --- | --- |
| a | Sell-in trong hạn mức: tạo, tiếp nhận, duyệt, xuất, nhận, xác nhận đối soát, job đêm/chốt ngày | Tổng 100.000 VND, nhận 10, một hóa đơn, PERIOD_CLOSED, kỳ CLOSED, không ngoại lệ mở, chạy lại không thêm lịch sử chốt |
| b | Vượt hạn mức | HELD_CREDIT; ASM và người tạo bị 403; RSM và lãnh đạo duyệt được; createdBy giả trong payload bị bỏ qua; audit đủ hai lần duyệt vượt |
| c | Sell-out đặt 9, xác nhận 7, nhận 5, giao bù 2 | Tự tạo ticket/việc; duyệt giao bù chưa đóng đơn; có bước giao và nhận bù; nhận đủ 7 mới CLOSED và đóng đúng ticket/việc thiếu hàng |
| d | Mobile mất phản hồi sau commit | Gửi lại cùng khóa, chỉ một order_so; nháp giữ được orderId sau retry |
| e | Kỳ đã chốt và điều chỉnh | Chặn sửa trực tiếp đơn/dòng hàng/snapshot/kỳ; người lập có cả hai quyền vẫn không tự duyệt; người khác duyệt; phiếu đã duyệt bất biến; báo cáo chính thức cộng đúng điều chỉnh |
| f | RBAC, tenant, sửa ID/JWT | 16 vai × 109 quyền; tenant khác không xem/nhận hộ đơn; điểm bán ngoài tenant/tuyến bị chặn; JWT sửa chữ ký bị 401 |

Trạng thái/lần chạy và số khẳng định thực tế nằm trong `backend/target/p7/results.json` và `results.md`; chi tiết thời gian ở `performance.json`, dọn tài nguyên ở `cleanup.json`, log backend ở `backend-tests.log`, `server.log`.

## Ma trận quyền độc lập

Đã tìm được workbook gốc [SYNERA_PhanQuyen_RBAC.xlsx](<D:/Tài liệu tải xuống/SYNERA_PhanQuyen_RBAC.xlsx>). Snapshot test `backend/src/test/resources/acceptance/rbac-oracle.json` được trích **chỉ đọc** từ sheet Matrix/Permissions, lưu SHA-256 nguồn. Bỏ dòng chú giải cuối bảng, giữ 16 vai × 108 quyền gốc = 1.728 cặp. Phần mở rộng P5 `foundation:import` có 16 cặp riêng, chỉ NSX_ADMIN/NPP_OWNER/NPP_STAFF được A. Tổng so với `/auth/me` là 1.744 cặp; không lấy seed làm kỳ vọng duy nhất.

`RbacWorkbookOracleTest` đối chiếu scope/grant và cờ nhạy cảm audit của seed với nguồn độc lập. Fixture matrix dùng tài khoản một vai; tài khoản kép kế toán+lãnh đạo chỉ dùng riêng để kiểm maker/checker không thể bị vượt bằng cách có cả hai quyền.

Khi chủ động cập nhật workbook, tạo lại snapshot để review diff:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/export-rbac-oracle.ps1 -Path '<source>/SYNERA_PhanQuyen_RBAC.xlsx'
```

## Hiệu năng

Seed riêng cho tenant benchmark: 25.000 sell-in + 25.000 sell-out, 50.000 dòng hàng, 5.000 điểm bán, 100 NPP. Không đưa vào tenant nghiệp vụ của kịch bản a–f. ANALYZE sau seed. Đo HTTP trên localhost sau warm-up, 7 mẫu/endpoint, page size 50; p50 là trung vị và p95 là mẫu cao nhất của 7 mẫu. Đây là benchmark cơ bản local, không suy ra SLA Railway.

Đo số câu SQL bằng `pg_stat_statements` trước/sau request với page size 1 và 50; bỏ truy vấn đo và BEGIN/COMMIT. Không dùng số câu SQL viết tay trong test để kết luận N+1. Bộ test bắt lỗi nếu số câu tăng theo số dòng hoặc p95 vượt ngưỡng kiểm thử cơ bản 2 giây. Có EXPLAIN ANALYZE/BUFFERS cho danh sách sell-in ở `sell-in-plan.json`.

Kết quả lượt `6132aac17518`, cùng bộ 50.000 đơn / 50.000 dòng hàng / 5.000 điểm bán:

| API | p50 (ms) | p95 (ms) | SQL trang 1 → 50 | Kết quả |
| --- | ---: | ---: | --- | --- |
| Sell-in | 416,37 | 619,90 | 2 → 2 | PASS |
| Sell-out | 768,30 | 882,13 | 2 → 2 | PASS |
| Điểm bán | 177,29 | 221,80 | 2 → 2 | PASS |
| NPP | 109,67 | 140,91 | 2 → 2 | PASS |
| Đối soát vận hành | 942,47 | 1.092,30 | 5 → 5 | PASS |

Thời gian SQL `databaseMsAt1/50` được đo ở hai request riêng bằng chênh lệch `total_exec_time`, không phải cùng bảy mẫu HTTP; không so trực tiếp hai cột này với p50/p95. Các API/biến thể ngoài năm endpoint đo trên chưa có kết luận N+1 từ benchmark này.

Lượt trước sửa ghi nhận p95 sell-out 914,42 ms, điểm bán 548,50 ms; báo cáo đối soát tăng 5 → 54 câu SQL. Sau gom truy vấn đối chiếu và chỉ mục V14, số câu báo cáo giữ 5 → 5. Thời gian HTTP dao động theo tải máy; không suy ra mức cải thiện bằng cách so hai lượt có tải khác nhau. Số cuối của lần nghiệm thu được lưu trong `performance.json`; `performance-before.json` giữ bằng chứng N+1 từ lần bắt lỗi.

## Lỗi phát hiện và thay đổi

| Lỗi | Sửa |
| --- | --- |
| Bộ lọc điểm bán thiếu ngoặc làm API danh sách/scope lỗi SQL | Đóng đúng biểu thức OR/EXISTS trong PartnerAccessService |
| Tra giá/khuyến mãi nối thành FROMprice_list_scope/FROMpromotion_scope | Thêm khoảng trắng ở biên text block trong EffectivePricingService/CatalogScopeService |
| Job khớp ba bên bị từ chối dù quyền chuyển trạng thái là SYSTEM, vì cột mô tả actor chứa thêm chữ | OrderFlowService quyết định theo required_permission=SYSTEM; không so cột mô tả actor bằng chuỗi cứng |
| Xác nhận đối soát đầu tiên lỗi vì PARTIALLY_CONFIRMED dài 19 ký tự nhưng cột status chỉ cho 16 | V16 mở rộng cột lên VARCHAR(32); kiểm tra lại bằng xác nhận hai bên trong kịch bản a |
| Báo cáo đối soát lọc một NPP nối thành ?GROUP và lỗi SQL | Ngăn cách rõ bộ lọc động với GROUP BY ở báo cáo chính thức và vận hành |
| Mở lại đơn dùng cache 30 giây của trước thao tác người khác, gửi version cũ | Chi tiết đơn luôn tải lại khi mở; khóa nút thao tác trong lúc refetch |
| UI xác nhận một phần không gửi ngày giao dự kiến mà backend yêu cầu | Thêm trường ngày giao và payload expectedDeliveryAt |
| Nháp mobile cùng tab không cập nhật ngay, phải chờ timer | Sự kiện thay đổi draft theo owner, refresh/sync ngay; vẫn dùng một flight và một idempotency key |
| Duyệt giao bù đóng đơn ngay; giới hạn giao bù dựa nhầm vào hàng đã nhận | V13 thêm ledger giao/nhận bù, hạn lượng theo phần thiếu/hỏng ban đầu; chỉ đóng sau nhận đủ; ticket/việc có liên kết rõ, không đóng việc thủ công cùng tên |
| Snapshot/phiếu điều chỉnh đã duyệt còn có thể sửa trực tiếp ngoài API | V15 bảo vệ snapshot/kỳ thật và phiếu đã duyệt; demo giữ khả năng seed/cleanup riêng |
| Báo cáo gọi đối chiếu riêng từng NPP | Một query theo lô cho các NPP của trang |
| Tra quan hệ điểm bán/NPP lặp scan | V14 thêm index đầu khóa outlet_id, npp_tenant_id và cặp đối tác đang hoạt động |
| Benchmark đối soát có lượt p95 3.074,85 ms, vượt ngưỡng 2 giây dù không còn N+1 | V17 thêm index theo tenant/NPP/ngày và index chứa số lượng/tiền dòng hàng; đo lại cùng ngưỡng, giữ thêm thời gian SQL từ pg_stat_statements |
| Windows giữ JAR runtime và ảnh hưởng lần build sau | Dùng Java executable thực, JAR runtime riêng từng run và dọn process do runner tạo |

Migration cũ V1–V12 được giữ nguyên; chỉ thêm V13–V17. Working tree đã có thay đổi P3–P6 trước P7, không reset hoặc gộp tùy tiện. P7 không triển khai Railway và không đánh dấu toàn bộ phần P4/native còn thiếu là đã hoàn thành.
