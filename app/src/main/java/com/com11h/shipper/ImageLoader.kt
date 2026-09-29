package com.com11h.shipper

import android.graphics.BitmapFactory
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Bộ tải ảnh đơn giản dùng OkHttp (project đã có sẵn thư viện này qua Api.kt),
 * không cần thêm thư viện ngoài (Glide/Coil/Picasso).
 * Dùng để hiển thị ảnh QR code thanh toán / nạp tiền lấy từ server lên ImageView.
 */
object ImageLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun load(imageView: ImageView, url: String) {
        if (url.isBlank()) return
        thread {
            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                imageView.post { imageView.setImageBitmap(bitmap) }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Bỏ qua lỗi tải ảnh QR để không làm crash app; ImageView sẽ để trống.
            }
        }
    }
}
