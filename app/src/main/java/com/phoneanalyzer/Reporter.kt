package com.phoneanalyzer

import org.json.JSONArray
import org.json.JSONObject

/** Формирование отчётов (JSON и текст) и отправка JSON в репозиторий GitHub. */
object Reporter {
    val notes = listOf(
        "Точный расход батареи и процессора по чужим приложениям сторонним приложениям Android не показывает: здесь только косвенные признаки (время на экране, трафик, автозапуск, службы, разрешения).",
        "Список реально запущенных служб чужих приложений недоступен; показаны объявленные в манифесте службы.",
        "Standby bucket чужих приложений недоступен сторонним приложениям и в отчёт не входит.",
        "Суточная статистика использования хранится Android ограниченное время (обычно около 7–10 дней), данные за более ранние дни могут быть неполными.",
        "Трафик по приложениям считается за 30 дней по системной статистике; мобильный трафик доступен на Android 10 и новее."
    )

    fun json(device: JSONObject, apps: List<AppData>, usage: Boolean, c: Collector): String {
        val o = JSONObject()
        o.put("formatVersion", 1)
        o.put("createdAt", System.currentTimeMillis())
        o.put("usageAccessGranted", usage)
        o.put("wifiTrafficAvailable", c.networkOk)
        o.put("mobileTrafficAvailable", c.mobileNetworkOk)
        o.put("storageSizesAvailable", c.storageOk)
        o.put("device", device)
        o.put("notes", JSONArray().also { a -> notes.forEach { a.put(it) } })
        o.put("apps", JSONArray().also { a -> apps.forEach { a.put(it.toJson()) } })
        return o.toString()
    }

    private fun top(title: String, apps: List<AppData>, value: (AppData) -> Long, fmt: (Long) -> String): String {
        val l = apps.filter { value(it) > 0 }.sortedByDescending(value).take(10)
        if (l.isEmpty()) return title + ": данных нет\n\n"
        return title + ":\n" + l.joinToString("\n") { "• " + it.label + " — " + fmt(value(it)) } + "\n\n"
    }

    fun text(device: JSONObject, apps: List<AppData>, usage: Boolean): String {
        val sb = StringBuilder()
        sb.append("Отчёт «Анализ телефона», ").append(Fmt.date(System.currentTimeMillis())).append("\n\n")
        sb.append("Устройство: ").append(device.getString("model")).append(", Android ")
            .append(device.getString("android")).append(" (SDK ").append(device.getInt("sdk")).append("), патч ")
            .append(device.optString("securityPatch")).append("\n")
        sb.append("ОЗУ: свободно ").append(Fmt.bytes(device.getLong("ramAvailable"))).append(" из ")
            .append(Fmt.bytes(device.getLong("ramTotal"))).append("\n")
        sb.append("Хранилище: свободно ").append(Fmt.bytes(device.getLong("storageFree"))).append(" из ")
            .append(Fmt.bytes(device.getLong("storageTotal"))).append("\n")
        sb.append("Батарея: ").append(device.optInt("batteryPercent")).append("%, температура ")
            .append(device.optDouble("batteryTempC")).append(" °C\n")
        sb.append("Доступ к статистике использования: ").append(if (usage) "есть" else "НЕТ (данные неполные)").append("\n")
        val user = apps.count { !it.system }
        sb.append("Приложений: ").append(apps.size).append(" (пользовательских ").append(user)
            .append(", системных ").append(apps.size - user).append(")\n\n")
        sb.append("Категории: ").append(apps.groupBy { it.category }.entries.joinToString(", ") { it.key + " — " + it.value.size }).append("\n\n")
        sb.append(top("ТОП-10 по занятому месту (приложение + данные + кэш)", apps, { it.storage }, { Fmt.bytes(it) }))
        sb.append(top("ТОП-10 по кэшу", apps, { it.cacheBytes }, { Fmt.bytes(it) }))
        sb.append(top("ТОП-10 по трафику за 30 дней", apps, { it.traffic30 }, { Fmt.bytes(it) }))
        sb.append(top("ТОП-10 по времени на экране за 30 дней", apps, { it.fg30 }, { Fmt.time(it) }))
        fun names(title: String, l: List<AppData>) {
            if (l.isEmpty()) return
            sb.append(title).append(" (").append(l.size).append("): ").append(l.take(40).joinToString(", ") { it.label })
            if (l.size > 40) sb.append(" и ещё ").append(l.size - 40)
            sb.append("\n\n")
        }
        names("Запускаются вместе с телефоном", apps.filter { it.bootStart && it.enabled })
        names("Объявляют foreground-службы", apps.filter { it.fgServices.isNotEmpty() })
        names("Фоновый доступ к местоположению", apps.filter { it.bgLocation })
        names("Службы специальных возможностей", apps.filter { it.accessibility })
        names("Доступ к уведомлениям", apps.filter { it.notifListener })
        names("Администраторы устройства", apps.filter { it.deviceAdmin })
        names("Исключены из оптимизации батареи", apps.filter { it.batteryUnrestricted && !it.system })
        for (cat in listOf("Можно удалить", "Можно отключить")) {
            val l = apps.filter { it.category == cat }
            if (l.isNotEmpty()) {
                sb.append(cat).append(":\n")
                l.take(60).forEach { sb.append("• ").append(it.label).append(" (").append(it.pkg).append(") — ").append(it.reason).append("\n") }
                sb.append("\n")
            }
        }
        sb.append("Ограничения:\n")
        notes.forEach { sb.append("• ").append(it).append("\n") }
        return sb.toString()
    }

}
