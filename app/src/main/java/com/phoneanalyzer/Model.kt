package com.phoneanalyzer

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Данные об одном приложении. Размеры -1 = недоступно. */
data class AppData(
    val pkg: String,
    val label: String,
    val version: String,
    val versionCode: Long,
    val targetSdk: Int,
    val system: Boolean,
    val enabled: Boolean,
    val installer: String?,
    val installed: Long,
    val updated: Long,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val lastUsed: Long,
    val fg30: Long,
    val fgDaily: List<Long>,
    val wifiDaily: List<Long>,
    val mobileDaily: List<Long>,
    val services: List<String>,
    val fgServices: List<String>,
    val providers: Int,
    val bootStart: Boolean,
    val netReceiver: Boolean,
    val packageReceiver: Boolean,
    val requested: List<String>,
    val granted: List<String>,
    val dangerousGranted: List<String>,
    val bgLocation: Boolean,
    val exactAlarm: Boolean,
    val notifListener: Boolean,
    val accessibility: Boolean,
    val deviceAdmin: Boolean,
    val batteryUnrestricted: Boolean,
    val category: String = "",
    val reason: String = ""
) {
    val traffic30: Long get() = wifiDaily.sum() + mobileDaily.sum()
    val fg7: Long get() = fgDaily.takeLast(7).sum()
    val storage: Long get() = (if (appBytes > 0) appBytes else 0) + (if (dataBytes > 0) dataBytes else 0) + (if (cacheBytes > 0) cacheBytes else 0)

    private fun arr(l: List<Any>) = JSONArray().also { a -> l.forEach { a.put(it) } }

    fun toJson(): JSONObject = JSONObject().apply {
        put("package", pkg); put("name", label); put("version", version); put("versionCode", versionCode)
        put("targetSdk", targetSdk); put("system", system); put("enabled", enabled)
        put("installer", installer ?: JSONObject.NULL)
        put("installedAt", installed); put("updatedAt", updated)
        put("appBytes", appBytes); put("dataBytes", dataBytes); put("cacheBytes", cacheBytes)
        put("lastUsedAt", lastUsed); put("foregroundMs30d", fg30); put("foregroundMs7d", fg7)
        put("foregroundMsDaily", arr(fgDaily))
        put("wifiBytesDaily", arr(wifiDaily)); put("mobileBytesDaily", arr(mobileDaily))
        put("trafficBytes30d", traffic30)
        put("services", arr(services)); put("foregroundServices", arr(fgServices)); put("providers", providers)
        put("startsOnBoot", bootStart); put("receivesNetworkChanges", netReceiver); put("receivesPackageEvents", packageReceiver)
        put("permissionsRequested", arr(requested)); put("permissionsGranted", arr(granted))
        put("dangerousGranted", arr(dangerousGranted))
        put("backgroundLocation", bgLocation); put("exactAlarmPermission", exactAlarm)
        put("notificationListener", notifListener); put("accessibilityService", accessibility)
        put("deviceAdmin", deviceAdmin); put("batteryOptimizationIgnored", batteryUnrestricted)
        put("category", category); put("reason", reason)
    }
}

object Fmt {
    fun bytes(b: Long): String = when {
        b < 0 -> "н/д"
        b >= (1L shl 30) -> "%.1f ГБ".format(b / (1L shl 30).toDouble())
        b >= (1L shl 20) -> "%.1f МБ".format(b / (1L shl 20).toDouble())
        b >= 1024 -> "%.0f КБ".format(b / 1024.0)
        else -> "$b Б"
    }

    fun time(ms: Long): String {
        val m = ms / 60000
        return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин"
    }

    fun date(ms: Long): String =
        if (ms <= 0) "никогда" else SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ms))

    fun stamp(ms: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ms))
}
