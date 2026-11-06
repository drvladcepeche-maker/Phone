package com.phoneanalyzer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object AdMonitorNotifications {
    fun notify(ctx: Context, sourcePackage: String, previousPackage: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "phone_analyzer_ad_monitor"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channel, "Контроль рекламных окон", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Проверь новое окно приложения")
            .setContentText("$sourcePackage сменило окно после $previousPackage")
            .setStyle(Notification.BigTextStyle().bigText(
                "Зафиксировано переключение окна между пакетами. Это может быть рекламное окно, системный диалог или обычный переход. Проверь источник перед блокировкой."
            ))
            .setAutoCancel(true)
            .build()
        try { manager.notify((sourcePackage.hashCode() and 0x7fffffff), notification) } catch (_: SecurityException) { }
    }
}
