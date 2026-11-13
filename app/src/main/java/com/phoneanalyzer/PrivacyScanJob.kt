package com.phoneanalyzer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.Build

/** Periodic, opt-in AppOps audit. It never collects location coordinates or network payloads. */
class PrivacyScanJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("privacy_monitor", false) || !Shizu.ready()) return@Thread
                val previous = prefs.getLong("privacy_last_scan", System.currentTimeMillis())
                val events = PrivacyAudit.scan(applicationContext)
                val now = System.currentTimeMillis()
                val fresh = events.filter { !it.rejected && it.background && it.timestamp > previous }
                prefs.edit().putLong("privacy_last_scan", now).apply()
                if (fresh.isNotEmpty()) notifyFindings(fresh)
            } catch (_: Exception) {
                // A later scheduled run retries; failures are not reported as clean scans.
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    private fun notifyFindings(events: List<PrivacyEvent>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "phone_analyzer_privacy"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channel, "Защита приватности", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val details = events.take(8).joinToString("\n") {
            "${it.appLabel}: ${it.resource} — ${PrivacyAudit.formatTime(it.timestamp)}"
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel)
                      else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Обнаружен фоновый доступ")
            .setContentText("${events.size} новых событий AppOps")
            .setStyle(Notification.BigTextStyle().bigText(details))
            .setAutoCancel(true)
            .build()
        try { manager.notify(4202, notification) } catch (_: SecurityException) { }
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
