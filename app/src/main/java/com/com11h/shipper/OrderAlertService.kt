package com.com11h.shipper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Cảnh báo đơn mới chạy nền cho Shipper.
 *
 * - Poll danh sách shipper_available_orders mỗi 8 giây.
 * - So sánh order_id, không dựa vào count nên không báo trùng khi danh sách
 *   thay đổi theo kiểu "1 đơn cũ mất + 1 đơn mới xuất hiện".
 * - Lần chạy đầu chỉ tạo baseline, không đọc các đơn đã có sẵn.
 * - Lưu pickup_id đã biết trong SharedPreferences, vì vậy reload/mở lại app
 *   không đọc lại đơn cũ.
 * - Chuông phát trước, sau đó TTS đọc câu cố định bằng giọng nam tiếng Việt
 *   nếu TTS engine có voice nam; nếu không có, dùng pitch thấp làm fallback.
 */
class OrderAlertService : Service() {

    companion object {
        private const val SERVICE_CHANNEL_ID = "shipper_order_service_v1"
        private const val ALERT_CHANNEL_ID = "shipper_new_orders_v1"
        private const val SERVICE_NOTIF_ID = 3100
        private const val ALERT_NOTIF_ID = 3101
        private const val POLL_MS = 8000L
        private const val PREFS = "shipper_order_alert"
        private const val LAST_AVAILABLE_IDS = "last_available_ids"
        private const val MESSAGE = "Có đơn hàng mới, cần giao nhanh!"
        private const val CHIME_DELAY_MS = 650L
    }

    private var worker: Thread? = null
    @Volatile private var running = false
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(SERVICE_NOTIF_ID, serviceNotification())
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            worker = thread(name = "ShipperOrderAlertPolling") { pollLoop() }
        }
        return START_STICKY
    }

    private fun pollLoop() {
        val session = SecureSession(this)

        while (running) {
            val token = session.token()
            val kcn = session.kcnId() ?: 0

            if (!token.isNullOrBlank() && kcn > 0) {
                try {
                    val api = Api(BuildConfig.API_BASE_URL, kcn, token)
                    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                    val j = api.call("shipper_available_orders")
                    val all = j.optJSONArray("orders") ?: org.json.JSONArray()
                    val arr = org.json.JSONArray()
                    for (i in 0 until all.length()) {
                        val o = all.optJSONObject(i)
                        if (o != null) arr.put(o)
                    }

                    val currentIds = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i)
                        // shipper_order_public() trả về order_id (không có pickup_id).
                        // Trước đây đọc pickup_id nên luôn nhận 0 -> currentIds luôn rỗng
                        // -> Shipper không bao giờ phát cảnh báo đơn mới.
                        val id = o?.optInt("order_id", 0) ?: 0
                        if (id > 0) currentIds.add(id.toString())
                    }

                    val hasBaseline = prefs.contains(LAST_AVAILABLE_IDS)
                    val previousIds = prefs.getString(LAST_AVAILABLE_IDS, "")
                        .orEmpty()
                        .split(",")
                        .filter { it.isNotBlank() }
                        .toSet()

                    // Chỉ báo những order_id thực sự mới.
                    val newIds = if (hasBaseline) {
                        currentIds.filter { it !in previousIds }
                    } else {
                        emptyList()
                    }

                    if (newIds.isNotEmpty()) {
                        showNewOrderAlert(newIds.size)
                    }

                    // Lưu toàn bộ danh sách hiện tại làm mốc cho lần sau.
                    prefs.edit()
                        .putString(
                            LAST_AVAILABLE_IDS,
                            currentIds.sorted().joinToString(",")
                        )
                        .apply()

                } catch (_: UnauthorizedException) {
                    // Token hết hạn: Activity sẽ xử lý khi shipper mở app.
                } catch (_: Exception) {
                    // Mạng/API lỗi: giữ nguyên baseline, vòng sau thử lại.
                }
            }

            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { result ->
            if (result != TextToSpeech.SUCCESS) return@TextToSpeech

            val engine = tts ?: return@TextToSpeech
            val vi = Locale("vi", "VN")

            runCatching {
                val voices = engine.voices.orEmpty()

                // Một số TTS engine (đặc biệt Google TTS) đặt "male" trong tên
                // voice. Ưu tiên voice nam tiếng Việt nếu engine cung cấp.
                val maleVoice = voices.firstOrNull {
                    it.locale.language.equals("vi", ignoreCase = true) &&
                        it.name.lowercase(Locale.ROOT).contains("male")
                }

                if (maleVoice != null) {
                    engine.voice = maleVoice
                } else {
                    engine.language = vi
                }

                // Fallback giúp các engine chỉ có một voice tiếng Việt phát
                // theo âm vực nam thấp hơn voice nữ mặc định.
                engine.setSpeechRate(0.92f)
                engine.setPitch(0.72f)
            }

            ttsReady = true
        }
    }

    private fun showNewOrderAlert(availableCount: Int) {
        // Chuông trước câu nói. Không dùng âm thanh mặc định của notification
        // để tránh phát chuông hai lần.
        playChime()

        mainHandler.postDelayed({
            speakNewOrder()
        }, CHIME_DELAY_MS)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val text = if (availableCount > 1) {
            "Có $availableCount đơn sẵn sàng để nhận"
        } else {
            "Có đơn mới sẵn sàng để nhận"
        }

        if (Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("🔔 ĐƠN HÀNG MỚI")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .build()

            runCatching {
                NotificationManagerCompat.from(this).notify(ALERT_NOTIF_ID, notification)
            }
        }
    }

    private fun playChime() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(
                this,
                RingtoneManager.TYPE_NOTIFICATION
            ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            RingtoneManager.getRingtone(this, uri)?.play()
        }
    }

    private fun speakNewOrder() {
        if (!ttsReady) return

        runCatching {
            tts?.speak(
                MESSAGE,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "shipper_new_order_${System.currentTimeMillis()}"
            )
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return

        val nm = getSystemService(NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "Dịch vụ kiểm tra đơn hàng",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Duy trì kiểm tra đơn hàng mới cho App Shipper"
            setSound(null, null)
            enableVibration(false)
        }

        // Âm thanh được phát thủ công: chuông -> TTS.
        // Vì vậy notification channel không phát thêm chuông thứ hai.
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "🔔 Đơn hàng mới",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Cảnh báo khi có đơn hàng mới sẵn sàng để giao"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(null, null)
        }

        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(alertChannel)
    }

    private fun serviceNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("FOOD KCN SHIPPER")
            .setContentText("Đang kiểm tra đơn hàng mới…")
            .setOngoing(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        mainHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
