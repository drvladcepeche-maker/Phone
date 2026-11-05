package com.phoneanalyzer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.Build

/** Ежедневный снимок состояния. Ошибки и результат видны в уведомлениях. */
class SnapshotJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                val collector = Collector(applicationContext)
                val device = collector.device()
                val apps = collector.collect()
                Snapshots.save(applicationContext, apps, device)
                notifyResult(4101, "Анализ телефона", "Снимок сохранён. Приложений: ${apps.size}.")
            } catch (e: Exception) {
                notifyResult(4102, "Анализ телефона: ошибка", e.message ?: "Не удалось сделать снимок.")
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    private fun notifyResult(id: Int, title: String, message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "phone_analyzer_jobs"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channel, "Фоновые задачи", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel)
                      else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        try { manager.notify(id, notification) } catch (_: SecurityException) { }
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
