package com.phoneanalyzer

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Opt-in window-event observer. It records package/class metadata only:
 * no view text, passwords, messages, clicks, or screen contents are read.
 */
class AdWindowMonitorService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val sourcePkg = event.packageName?.toString()?.trim().orEmpty()
        if (sourcePkg.isBlank() || sourcePkg == packageName || sourcePkg in ignoredPackages) return
        if (!Shizu.ready()) return
        val (appOpCode, appOp) = try { Shizu.execAppOpsGetOverlay(sourcePkg) } catch (_: Exception) { return }
        if (appOpCode != 0 || !appOp.contains("allow", ignoreCase = true)) return
        val prefs = getSharedPreferences("ad_monitor", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previousPkg = prefs.getString("last_window_package", null)
        val previousAt = prefs.getLong("last_window_at", 0L)
        prefs.edit().putString("last_window_package", sourcePkg).putLong("last_window_at", now).apply()

        // A package switch immediately after another app's window is a lead, not proof of an ad.
        if (previousPkg == null || previousPkg == sourcePkg || now - previousAt > 5000L) return
        val eventClass = event.className?.toString()?.take(180).orEmpty()
        val rows = try { JSONArray(prefs.getString("events", "[]")) } catch (_: Exception) { JSONArray() }
        val key = "$sourcePkg|$previousPkg|$eventClass"
        var duplicate = false
        for (i in 0 until rows.length()) {
            if (rows.optJSONObject(i)?.optString("key") == key &&
                now - rows.optJSONObject(i)!!.optLong("time") < 60_000L) {
                duplicate = true
                break
            }
        }
        if (!duplicate) {
            rows.put(JSONObject().apply {
                put("key", key)
                put("time", now)
                put("source", sourcePkg)
                put("previous", previousPkg)
                put("class", eventClass)
                put("event", event.eventType)
                put("review", true)
            })
            while (rows.length() > 100) rows.remove(0)
            prefs.edit().putString("events", rows.toString()).apply()
            AdMonitorNotifications.notify(this, sourcePkg, previousPkg)
        }
    }

    override fun onInterrupt() = Unit

    private val ignoredPackages = setOf(
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.launcher3",
        "com.miui.home",
        "com.miui.securitycenter"
    )
}
