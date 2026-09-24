package com.com11h.shipper

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var api: Api
    private lateinit var session: SecureSession
    private lateinit var box: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SecureSession(this)

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }

        val savedToken = session.token()
        if (!savedToken.isNullOrBlank()) {
            val savedKcn = session.kcnId() ?: 1
            api = Api(BuildConfig.API_BASE_URL, savedKcn, savedToken)
            showApp()
        } else {
            showLogin()
        }
    }

    private fun shell() {
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    private fun showLogin() {
        shell()

        box.addView(title("🚚 VESO SHIPPER\nGiao vé số"))

        val u = field("Tài khoản")
        val p = field("Mật khẩu", true)
        box.addView(u)
        box.addView(p)

        val b = Button(this).apply {
            text = "ĐĂNG NHẬP"
        }
        status = TextView(this)

        box.addView(b)
        box.addView(status)

        b.setOnClickListener {
            async {
                val loginData = Api(BuildConfig.API_BASE_URL, 1).call(
                    "shipper_login",
                    JSONObject()
                        .put("username", u.text.toString().trim())
                        .put("password", p.text.toString())
                        .put("device", "VESO Shipper Android")
                )

                // Backend trả token bên trong data:
                // data = { token, expires_at, shipper }
                val token = loginData.optString("token").trim()
                if (token.isBlank()) {
                    throw ApiException("Đăng nhập thành công nhưng máy chủ không trả token.")
                }

                val shipper = loginData.optJSONObject("shipper")
                    ?: throw ApiException("Dữ liệu tài khoản shipper không hợp lệ.")

                val kcnId = shipper.optInt("kcn_id", 1).takeIf { it > 0 } ?: 1
                val name = shipper.optString("name")

                session.save(token, kcnId, name)
                api = Api(BuildConfig.API_BASE_URL, kcnId, token)

                ui {
                    showApp()
                }
            }
        }
    }

    private var showingAvailable = true

    private fun showApp() {
        shell()
        box.addView(title("🚚 VESO SHIPPER"))

        status = TextView(this)
        box.addView(status)

        val row = LinearLayout(this)
        val tabAvail = Button(this).apply { text = "ĐƠN CHỜ NHẬN" }
        val tabMine = Button(this).apply { text = "ĐƠN CỦA TÔI" }
        val logout = Button(this).apply { text = "THOÁT" }

        row.addView(tabAvail)
        row.addView(tabMine)
        row.addView(logout)
        box.addView(row)

        tabAvail.setOnClickListener {
            showingAvailable = true
            loadOrders()
        }

        tabMine.setOnClickListener {
            showingAvailable = false
            loadOrders()
        }

        logout.setOnClickListener {
            stopService(Intent(this, OrderAlertService::class.java))
            async {
                runCatching { api.call("shipper_logout") }
                session.clear()
                ui { showLogin() }
            }
        }

        startService(Intent(this, OrderAlertService::class.java))
        loadOrders()
    }

    private fun loadOrders() {
        val action =
            if (showingAvailable) "shipper_available_orders"
            else "shipper_my_orders"

        async {
            val data = api.call(action)
            val a = data.optJSONArray("orders") ?: org.json.JSONArray()

            ui {
                while (box.childCount > 3) {
                    box.removeViewAt(3)
                }

                if (!showingAvailable) {
                    val cod = data.optDouble("cod_pending_total", 0.0)
                    addText(
                        "💰 Tiền COD đang giữ: ${money(cod.toInt())} — nhớ nộp về công ty"
                    )
                }

                if (a.length() == 0) {
                    addText(
                        if (showingAvailable) {
                            "Chưa có đơn chờ nhận."
                        } else {
                            "Bạn chưa nhận đơn nào."
                        }
                    )
                } else {
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val customer = o.optString("customer")
                        val phone = o.optString("phone")
                        val phoneText = if (phone.isNotBlank()) " • $phone" else ""

                        val b = Button(this).apply {
                            text = buildString {
                                append(o.optString("code"))
                                append("\nKhách: ")
                                append(customer)
                                append(phoneText)
                                append("\n")
                                append(statusText(o.optString("status")))
                                append(" • ")
                                append(money(o.optInt("total")))
                                append(" (COD)")
                            }
                        }

                        b.setOnClickListener {
                            orderMenu(o)
                        }
                        box.addView(b)
                    }
                }
            }
        }
    }

    private fun statusText(st: String): String = when (st) {
        "Đã xác nhận" -> "Đã xác nhận"
        "Đang nấu" -> "Đang chuẩn bị"
        "READY_FOR_PICKUP" -> if (showingAvailable) {
            "Đã sẵn sàng — có thể nhận"
        } else {
            "Đã nhận — có thể bắt đầu giao"
        }
        "Đang giao" -> "Đang giao - chờ OTP"
        "Hoàn thành" -> "Đã hoàn thành"
        else -> st
    }

    private fun orderMenu(o: JSONObject) {
        val st = o.optString("status")
        val orderId = o.optInt("order_id", 0)

        if (orderId <= 0) {
            status.text = "Dữ liệu đơn hàng không có order_id."
            return
        }

        when {
            showingAvailable && st == "READY_FOR_PICKUP" -> {
                AlertDialog.Builder(this)
                    .setTitle(o.optString("code"))
                    .setMessage(
                        "Khách: ${o.optString("customer")}\n" +
                            "Địa chỉ giao: ${o.optString("address")}\n" +
                            "Tiền COD: ${money(o.optInt("cod_amount", o.optInt("total")))}"
                    )
                    .setPositiveButton("NHẬN ĐƠN") { _, _ ->
                        async {
                            api.call(
                                "shipper_claim_order",
                                JSONObject().put("order_id", orderId)
                            )
                            ui { loadOrders() }
                        }
                    }
                    .setNegativeButton("HỦY", null)
                    .show()
            }

            !showingAvailable && st == "READY_FOR_PICKUP" -> {
                AlertDialog.Builder(this)
                    .setTitle(o.optString("code"))
                    .setMessage(
                        "Khách: ${o.optString("customer")}\n" +
                            "SĐT: ${o.optString("phone")}\n" +
                            "Địa chỉ giao: ${o.optString("address")}\n\n" +
                            "Đã nhận đơn. Khi đã lấy vé và rời shop, bấm BẮT ĐẦU GIAO."
                    )
                    .setPositiveButton("BẮT ĐẦU GIAO") { _, _ ->
                        async {
                            api.call(
                                "shipper_start_delivery",
                                JSONObject().put("order_id", orderId)
                            )
                            ui { loadOrders() }
                        }
                    }
                    .setNegativeButton("HỦY", null)
                    .show()
            }

            !showingAvailable && st == "Đang giao" -> {
                val otpField = EditText(this).apply {
                    hint = "Nhập mã OTP 4 số khách đọc"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }

                AlertDialog.Builder(this)
                    .setTitle("Xác nhận giao vé - ${o.optString("code")}")
                    .setMessage(
                        "Thu ${money(o.optInt("cod_amount", o.optInt("total")))} " +
                            "tiền mặt (COD) từ khách, sau đó nhập mã OTP khách đọc cho bạn để hoàn tất."
                    )
                    .setView(otpField)
                    .setPositiveButton("XÁC NHẬN") { _, _ ->
                        async {
                            api.call(
                                "shipper_confirm_otp",
                                JSONObject()
                                    .put("order_id", orderId)
                                    .put("otp", otpField.text.toString().trim())
                            )
                            ui { loadOrders() }
                        }
                    }
                    .setNegativeButton("HỦY", null)
                    .show()
            }

            else -> {
                AlertDialog.Builder(this)
                    .setTitle(o.optString("code"))
                    .setMessage(
                        "Khách: ${o.optString("customer")}\n" +
                            "Trạng thái: ${statusText(st)}\n" +
                            "Địa chỉ: ${o.optString("address")}"
                    )
                    .setPositiveButton("ĐÓNG", null)
                    .show()
            }
        }
    }

    private fun addText(s: String) {
        box.addView(
            TextView(this).apply {
                text = s
                textSize = 16f
                setPadding(0, 14, 0, 14)
            }
        )
    }

    private fun title(s: String) = TextView(this).apply {
        text = s
        textSize = 27f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, 12)
    }

    private fun field(h: String, p: Boolean = false) =
        EditText(this).apply {
            hint = h
            if (p) {
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

    private fun money(n: Int) = "%,d đ".format(n).replace(',', '.')

    private fun ui(f: () -> Unit) = runOnUiThread(f)

    private fun async(f: () -> Unit) {
        thread {
            try {
                f()
            } catch (e: Exception) {
                ui {
                    status.text = e.message ?: "Có lỗi xảy ra"
                }
            }
        }
    }
}
