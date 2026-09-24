package com.com11h.shipper

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Fallback đồng bộ nền.
 *
 * Cảnh báo giọng nói/notification do OrderAlertService xử lý để tránh
 * thông báo trùng khi WorkManager chạy lại.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val session = SecureSession(applicationContext)
        val token = session.token() ?: return Result.success()
        val kcn = session.kcnId() ?: return Result.success()

        return try {
            Api(BuildConfig.API_BASE_URL, kcn, token).call("shipper_available_orders")
            Result.success()
        } catch (_: UnauthorizedException) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
