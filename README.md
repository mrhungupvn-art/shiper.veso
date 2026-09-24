# COM11H Shipper Android — Production baseline

Android native Kotlin app cho shipper, khóa nghiệp vụ theo KCN đăng nhập.
Đồng bộ trực tiếp với web admin (com11h.com/admin/shippers.php,
admin/shipper_cod.php) vì dùng CHUNG database/bảng qua API — không có dữ
liệu riêng nào chỉ tồn tại trong app.

## Đã hoàn thiện
- Đăng nhập bằng tài khoản shipper (`shipper_accounts`), server cấp Bearer
  token riêng cho app shipper (khác token app khách hàng và app shop).
- Token được mã hóa AES/GCM bằng Android Keystore; không lưu plaintext.
- Tự khôi phục phiên đăng nhập sau khi mở app.
- Danh sách đơn sẵn sàng để nhận trong đúng KCN đang đăng nhập.
- Nhận đơn (khoá bằng UNIQUE KEY ở server — 2 shipper không thể nhận trùng
  1 đơn dù bấm cùng lúc).
- Bắt đầu giao: chuyển đơn sang "Đang giao" + server sinh mã OTP 4 số hiển
  thị cho KHÁCH xem trên web (account.php / trang theo dõi đơn) — khách đọc
  mã này cho shipper lúc nhận hàng.
- Xác nhận OTP để hoàn tất đơn: server tự ghi nhận COD đã thu (nếu đơn chưa
  thanh toán online) và cập nhật đơn sang "Hoàn thành" — admin thấy ngay lập
  tức trên trang quản lý.
- Hiển thị tổng COD đang giữ (chưa nộp).
- Mở chỉ đường bằng Google Maps/ứng dụng bản đồ mặc định qua `geo:`.
- Poll đồng bộ nền bằng WorkManager, thông báo khi pool tăng.
- Kéo để làm mới (SwipeRefreshLayout).
- UI dọc, tối ưu thao tác một tay, hỗ trợ portrait.
- API URL cấu hình bằng `BuildConfig.API_BASE_URL` từ `API_BASE_URL`.
- GitHub Actions build APK release.

## API contract đang dùng
`https://com11h.com/api/index.php` (xem chú thích đầy đủ trong `api/index.php`
và các hàm nghiệp vụ trong `core.php` phía server — 2 bên PHẢI khớp nhau).

Actions:
- `shipper_login`             `{"username","password"}`
- `shipper_available_orders`  (GET) — đơn Shop đã chuẩn bị xong, chưa có shipper nhận
- `shipper_my_orders`         (GET) — đơn đang giữ + `cod_pending_total` (tiền COD đang giữ chưa nộp)
- `shipper_claim_order`       `{"order_id"}` — nhận đơn (khoá UNIQUE, 2 shipper không nhận trùng)
- `shipper_picked`            `{"order_id"}` — đã lấy vé tại Shop
- `shipper_start_delivery`    `{"order_id"}` — bắt đầu giao, server sinh mã OTP 4 số cho khách xem
- `shipper_confirm_otp`       `{"order_id","otp"}` — khách đọc mã OTP, shipper nhập để hoàn tất + ghi nhận đã thu COD
- `shipper_logout`

Thanh toán hiện tại là **COD (thu tiền khi giao)**: đơn KHÔNG được đánh dấu
"đã thanh toán" khi Shop xác nhận — chỉ chuyển `payment_status=PAID` khi
shipper xác nhận đúng mã OTP ở bước cuối. Server tự cộng số tiền vào sổ COD
của shipper (`veso_shipper_ledger`); admin đối soát/thu nộp tại
`admin/shipper_cod.php`.

Headers bắt buộc cho mọi request:
- `Accept: application/json`
- `Authorization: Bearer <token>` — trừ `shipper_login`.

Field 1 đơn hàng trả về (`shipper_available_orders` / `shipper_my_orders`):
`order_id`, `code`, `status`, `customer`, `phone`, `address`, `note`,
`delivery_time`, `total`, `cod_amount` (0 nếu khách đã thanh toán online),
`shipper_fee`, `shipping_distance_km`, `payment_status`, `created_at`.
Riêng `shipper_my_orders` có thêm `otp_active` (true = đã bấm "Bắt đầu giao",
đang chờ khách đọc OTP) và tổng `cod_pending_total` ở cấp `data`.

**Không có khái niệm "nhiều tiệm/store trong 1 đơn" hay quét QR mỗi tiệm** —
mỗi KCN chỉ có 1 bếp trung tâm, 1 đơn = 1 điểm giao duy nhất.

## GitHub Secrets
Thiết lập:
- `API_BASE_URL` — ví dụ `https://com11h.com`
- `KEYSTORE_BASE64` — file keystore release đã base64
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Nếu chưa có keystore, workflow vẫn build release APK nhưng APK sẽ không được ký release. Production nên bắt buộc dùng keystore riêng của tổ chức và bảo vệ secrets bằng Environment/branch protection.

## Local build
Mở `shipper-app` bằng Android Studio hoặc chạy Gradle 8.10.2 + JDK 17:

```bash
gradle :app:assembleDebug
```

Release:

```bash
API_BASE_URL=https://com11h.com gradle :app:assembleRelease
```
