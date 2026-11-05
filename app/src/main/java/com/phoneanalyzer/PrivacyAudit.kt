package com.phoneanalyzer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PrivacyEvent(
    val packageName: String,
    val appLabel: String,
    val operation: String,
    val resource: String,
    val timestamp: Long,
    val background: Boolean,
    val rejected: Boolean,
    val mode: String
)

object PrivacyAudit {
    private val operations = mapOf(
        "FINE_LOCATION" to "Геолокация (точная)",
        "COARSE_LOCATION" to "Геолокация (приблизительная)",
        "MONITOR_LOCATION" to "Геолокация (мониторинг)",
        "MONITOR_HIGH_POWER_LOCATION" to "Геолокация (высокая точность)",
        "CAMERA" to "Камера",
        "RECORD_AUDIO" to "Микрофон",
        "BODY_SENSORS" to "Датчики тела",
        "ACTIVITY_RECOGNITION" to "Распознавание активности"
    )
    private val packageLine = Regex("^Package\\s+([^:]+):")
    private val opLine = Regex("^\\s{2}([A-Z][A-Z0-9_]+)\\s+\\(([^)]*)\\):")
    private val accessLine = Regex("(Access|Reject):\\s*\\[([^]]+)]\\s*(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?)")

    fun scan(ctx: Context): List<PrivacyEvent> {
        check(Shizu.ready()) { "Для чтения системного журнала требуется запущенный Shizuku с разрешением." }
        val (code, dump) = Shizu.execAppOpsDump()
        check(code == 0 && dump.isNotBlank()) { "Android не вернул журнал AppOps." }
        var pkg = ""
        var op = ""
        var mode = ""
        val events = ArrayList<PrivacyEvent>()
        val now = System.currentTimeMillis()
        val dateFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        ).onEach { it.isLenient = false }

        for (line in dump.lineSequence()) {
            packageLine.find(line)?.let { pkg = it.groupValues[1].trim(); op = ""; mode = "" }
            opLine.find(line)?.let { op = it.groupValues[1]; mode = it.groupValues[2] }
            val resource = operations[op] ?: continue
            if (pkg.isBlank()) continue
            val match = accessLine.find(line) ?: continue
            val timestamp = dateFormats.firstNotNullOfOrNull { fmt ->
                try { fmt.parse(match.groupValues[3])?.time } catch (_: Exception) { null }
            } ?: continue
            if (timestamp > now + 5 * 60 * 1000L || now - timestamp > 7L * 24 * 3600 * 1000) continue
            val state = match.groupValues[2].lowercase(Locale.US)
            val label = try {
                ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
            events.add(PrivacyEvent(
                packageName = pkg, appLabel = label, operation = op, resource = resource,
                timestamp = timestamp, background = state.contains("bg"),
                rejected = match.groupValues[1] == "Reject", mode = mode
            ))
        }
        return events.distinctBy { "${it.packageName}|${it.operation}|${it.timestamp}|${it.rejected}" }
            .sortedByDescending { it.timestamp }.take(300)
    }

    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
