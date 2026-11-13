package com.phoneanalyzer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException

/** Upload survives activity closure and is retried by WorkManager when the network returns. */
class ReportUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val path = inputData.getString("report_path") ?: return Result.failure()
        val reportId = inputData.getString("report_id") ?: return Result.failure()
        val file = File(path)
        if (!file.isFile) return Result.failure()

        return try {
            val response = ServerBridge.uploadReport(applicationContext, file.readText(Charsets.UTF_8), reportId)
            file.delete()
            val count = response.optJSONObject("summary")?.optInt("appCount") ?: 0
            notifyResult(4201, "Анализ телефона", "Отчёт принят сервером. Приложений: $count.")
            Result.success()
        } catch (e: IllegalStateException) {
            file.delete()
            notifyResult(4202, "Ошибка отправки отчёта", e.message ?: "Сервер отклонил отчёт.")
            Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount >= 5) {
                file.delete()
                notifyResult(4202, "Ошибка отправки отчёта", e.message ?: "Не удалось связаться с сервером.")
                Result.failure()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            file.delete()
            notifyResult(4202, "Ошибка отправки отчёта", e.message ?: "Не удалось отправить отчёт.")
            Result.failure()
        }
    }

    private fun notifyResult(id: Int, title: String, message: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "phone_analyzer_jobs"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channel, "Фоновые задачи", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(applicationContext, channel)
                      else Notification.Builder(applicationContext)
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        try { manager.notify(id, notification) } catch (_: SecurityException) { }
    }
}
