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
        val action = if (showingAvailable) "shipper_available_orders" else "shipper_my_orders"

        async {
            val data = api.call(action)
            val orders = data.optJSONArray("orders") ?: org.json.JSONArray()

            ui {
                while (box.childCount > 3) box.removeViewAt(3)

                if (!showingAvailable) {
                    val cod = data.optDouble("cod_pending_total", 0.0)
                    addText("💰 Tiền COD đang giữ: ${money(cod.toInt())} — nhớ nộp về công ty")
                }

                if (orders.length() == 0) {
                    addText(
                        if (showingAvailable) "Chưa có đơn chờ nhận."
                        else "Bạn chưa nhận đơn nào."
                    )
                } else {
                    for (i in 0 until orders.length()) {
                        addOrderCard(orders.getJSONObject(i))
                    }
                }

                status.text = if (showingAvailable) {
                    "Đơn chờ nhận: ${orders.length()}"
                } else {
                    "Đơn đang cầm: ${orders.length()}"
                }
            }
        }
    }

    /**
     * Hiển thị đơn theo đúng luồng KCN:
     *   Đơn chờ nhận -> NHẬN ĐƠN
     *   Đơn đã thuộc shipper nhưng chưa giao -> BẮT ĐẦU GIAO
     *   Đã bắt đầu giao -> nhập OTP khách đọc -> HOÀN THÀNH
     *
     * Lưu ý quan trọng: orders.status = "Đã xác nhận" KHÔNG có nghĩa là
     * shipper đã giao xong. Với endpoint shipper_my_orders, đơn đã nằm trong
     * shipper_deliveries của chính shipper và delivered_at vẫn NULL, nên đây
     * chính xác là trạng thái "ĐÃ NHẬN ĐƠN — CHƯA BẮT ĐẦU GIAO".
     */
    private fun addOrderCard(o: JSONObject) {
        val orderId = o.optInt("order_id", 0)
        val st = o.optString("status", "")
        val otpActive = o.optBoolean("otp_active", false)
        val claimed = !showingAvailable || o.optBoolean("claimed", false)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }

        val code = o.optString("code", "-")
        c.addView(TextView(this).apply {
            text = code
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        c.addView(TextView(this).apply {
            text = "Khách: ${o.optString("customer", "-")}"
            textSize = 16f
            setPadding(0, 6, 0, 0)
        })

        val phone = o.optString("phone", "").trim()
        if (phone.isNotBlank()) {
            val phoneRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 0)
            }
            phoneRow.addView(TextView(this).apply {
                text = "📞 $phone"
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            phoneRow.addView(Button(this).apply {
                text = "GỌI"
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${android.net.Uri.encode(phone)}")))
                    }.onFailure { toast("Không thể mở cuộc gọi") }
                }
            })
            c.addView(phoneRow)
        }

        c.addView(TextView(this).apply {
            text = "📍 ${o.optString("address", "-")}"
            textSize = 15f
            setPadding(0, 6, 0, 0)
        })

        val cod = o.optInt("cod_amount", o.optInt("total", 0))
        c.addView(TextView(this).apply {
            text = "💰 COD: ${money(cod)}"
            textSize = 15f
            setPadding(0, 6, 0, 0)
        })

        val note = o.optString("note", "")
        if (note.contains("[VIETLOTT]")) {
            c.addView(TextView(this).apply {
                text = "🎯 VIETLOTT: ${note.substringAfter("[VIETLOTT]").trim()}"
                textSize = 14f
                setPadding(0, 8, 0, 0)
            })
        }

        if (showingAvailable) {
            val ready = st == "READY_FOR_PICKUP"
            c.addView(TextView(this).apply {
                text = if (ready) {
                    "🟢 ĐÃ SẴN SÀNG — CÓ THỂ NHẬN ĐƠN"
                } else {
                    "🟡 ${statusText(st)} — CHƯA THỂ NHẬN"
                }
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 10, 0, 6)
            })

            val claim = Button(this).apply {
                text = if (ready) "✅ NHẬN ĐƠN" else "⏳ CHƯA SẴN SÀNG"
                isEnabled = ready
            }
            c.addView(claim)
            claim.setOnClickListener {
                if (!ready || orderId <= 0) return@setOnClickListener
                claim.isEnabled = false
                async {
                    api.call(
                        "shipper_claim_order",
                        JSONObject().put("order_id", orderId)
                    )
                    ui {
                        toast("Đã nhận đơn. Vui lòng vào ĐƠN CỦA TÔI để bắt đầu giao.")
                        showingAvailable = false
                        loadOrders()
                    }
                }
            }
        } else {
            // Đơn nằm trong shipper_my_orders => shipper đã CLAIM.
            // Không dùng orders.status để quyết định đã giao hay chưa.
            if (otpActive || st == "Đang giao") {
                c.addView(TextView(this).apply {
                    text = "🚚 ĐANG GIAO — CHỜ OTP KHÁCH"
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 10, 0, 8)
                })

                val otpRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val otp = EditText(this).apply {
                    hint = "OTP 4 số khách đọc"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val confirm = Button(this).apply { text = "XÁC NHẬN GIAO" }
                otpRow.addView(otp)
                otpRow.addView(confirm)
                c.addView(otpRow)

                confirm.setOnClickListener {
                    val codeOtp = otp.text.toString().trim()
                    if (codeOtp.length != 4) {
                        toast("OTP phải gồm đúng 4 số")
                        return@setOnClickListener
                    }
                    confirm.isEnabled = false
                    async {
                        api.call(
                            "shipper_confirm_otp",
                            JSONObject().put("order_id", orderId).put("otp", codeOtp)
                        )
                        ui {
                            toast("Đã giao thành công")
                            loadOrders()
                        }
                    }
                }
            } else {
                c.addView(TextView(this).apply {
                    text = "📦 ĐÃ NHẬN ĐƠN — CHƯA BẮT ĐẦU GIAO"
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 10, 0, 8)
                })
                c.addView(TextView(this).apply {
                    text = "Đã lấy đơn khỏi danh sách chờ. Chỉ bấm BẮT ĐẦU GIAO khi đã nhận vé tại Shop và rời Shop."
                    textSize = 14f
                    setPadding(0, 0, 0, 8)
                })

                val start = Button(this).apply { text = "🛵 BẮT ĐẦU GIAO" }
                c.addView(start)
                start.setOnClickListener {
                    if (!claimed || orderId <= 0) return@setOnClickListener
                    start.isEnabled = false
                    async {
                        api.call(
                            "shipper_start_delivery",
                            JSONObject().put("order_id", orderId)
                        )
                        ui {
                            toast("Đã bắt đầu giao. Khi tới khách, yêu cầu khách đọc OTP.")
                            loadOrders()
                        }
                    }
                }
            }
        }

        val nav = Button(this).apply { text = "🗺️ CHỈ ĐƯỜNG" }
        c.addView(nav)
        nav.setOnClickListener {
            val address = o.optString("address", "").trim()
            if (address.isBlank()) {
                toast("Đơn chưa có địa chỉ giao")
                return@setOnClickListener
            }
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=${android.net.Uri.encode(address)}")))
            }.onFailure { toast("Không mở được bản đồ") }
        }

        box.addView(c)
    }

    private fun statusText(st: String): String = when (st) {
        "Đã xác nhận" -> "ĐÃ XÁC NHẬN"
        "Đang nấu" -> "SHOP ĐANG CHUẨN BỊ"
        "READY_FOR_PICKUP" -> "ĐÃ SẴN SÀNG"
        "Đang giao" -> "ĐANG GIAO"
        "Hoàn thành" -> "ĐÃ HOÀN THÀNH"
        else -> st.ifBlank { "CHƯA RÕ TRẠNG THÁI" }
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
