package com.com11h.partner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Đồng bộ nền cho app Partner. Bản cũ chỉ gọi API rồi kết thúc nên khi app
 * đang nền Partner không hề nhận thông báo. Bản này lưu "dấu vân tay" danh
 * sách đơn pending và báo khi xuất hiện pickup mới, kể cả trường hợp tổng số
 * đơn không đổi.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val s = SecureSession(applicationContext)
        val t = s.token() ?: return Result.success()
        val k = s.kcnId() ?: return Result.success()
        return try {
            val json = Api(BuildConfig.API_BASE_URL, k, t).call("partner_pending_pickups")
            val orders = json.optJSONObject("data")?.optJSONArray("pickups")
            val ids = buildList {
                if (orders != null) for (i in 0 until orders.length()) {
                    add(orders.optJSONObject(i)?.optInt("pickup_id", 0)?.toString() ?: "0")
                }
            }.filter { it != "0" }.sorted().joinToString(",")

            val prefs = applicationContext.getSharedPreferences("partner_sync", Context.MODE_PRIVATE)
            val previous = prefs.getString("pending_ids", null)
            if (previous != null && ids != previous) {
                val old = previous.split(',').filter { it.isNotBlank() }.toSet()
                val current = ids.split(',').filter { it.isNotBlank() }.toSet()
                val added = current.count { it !in old }
                if (added > 0) notifyNewOrders(added)
            }
            prefs.edit().putString("pending_ids", ids).apply()
            Result.success()
        } catch (_: UnauthorizedException) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun notifyNewOrders(delta: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel("partner_orders", "Đơn mới", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val text = if (delta > 1) "Có $delta đơn mới cần xác nhận" else "Có đơn mới cần xác nhận"
        val notification = NotificationCompat.Builder(applicationContext, "partner_orders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("ĐƠN HÀNG MỚI")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(1201, notification) }
    }
}
