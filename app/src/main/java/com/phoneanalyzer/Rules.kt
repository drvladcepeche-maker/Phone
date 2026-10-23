package com.phoneanalyzer

/** Правила рекомендаций. Приложение ничего не отключает и не удаляет само. */
object Rules {
    private const val DAY = 24L * 3600 * 1000

    private val criticalPrefix = listOf(
        "com.android.systemui", "com.android.phone", "com.android.providers.", "com.android.settings",
        "com.android.launcher", "com.android.inputmethod", "com.android.bluetooth", "com.android.nfc",
        "com.android.server.telecom", "com.android.shell", "com.android.packageinstaller",
        "com.android.permissioncontroller", "com.android.vending", "com.google.android.gms",
        "com.google.android.gsf", "com.google.android.webview", "com.google.android.inputmethod",
        "com.google.android.permissioncontroller", "com.google.android.packageinstaller",
        "com.google.android.ext.", "com.android.networkstack", "com.android.cellbroadcast",
        "com.android.emergency", "com.android.certinstaller", "com.android.keychain",
        "com.android.location", "com.android.carrierconfig", "com.android.stk", "com.android.mms",
        "com.android.contacts", "com.android.dialer", "com.google.android.dialer",
        "com.google.android.contacts", "com.google.android.apps.messaging",
        "com.google.android.apps.nexuslauncher", "com.google.android.permission"
    )

    private val criticalWords = listOf(
        "systemui", "launcher", "framework", "telecom", "dialer", "keyboard", "inputmethod",
        "setupwizard", "accessibility", "webview", "bluetooth", "overlay", "provider"
    )

    private val optionalWords = listOf(
        "facebook", "instagram", "netflix", "spotify", "tiktok", "linkedin", "amazon", "booking",
        "game", "demo", "bixby", "hicloud", "micloud", "mipicks", "miui.msa", "miui.analytics",
        "adservices", "com.google.android.videos", "com.google.android.music",
        "com.google.android.apps.magazines", "com.google.android.apps.tachyon"
    )

    fun classify(a: AppData, now: Long, usageKnown: Boolean): Pair<String, String> {
        if (!a.enabled) return "Уже отключено" to "Приложение уже отключено в системе."
        val p = a.pkg
        if (a.system) {
            if (p == "android" || criticalPrefix.any { p.startsWith(it) } || criticalWords.any { p.contains(it) })
                return "Не трогать" to "Критичный компонент системы: отключение может нарушить работу телефона."
            if (optionalWords.any { p.contains(it) })
                return "Можно отключить" to "Необязательное предустановленное приложение (магазин, облако, игра, соцсеть или реклама). Отключается штатно, телефон работает без него."
            if (a.bootStart && usageKnown && a.fg30 < 5 * 60000L)
                return "Проверить" to "Системное приложение запускается вместе с телефоном, но почти не используется. Перед отключением убедитесь, что оно не нужно."
            return "Проверить" to "Системное приложение неизвестного назначения. Без понимания, зачем оно нужно, не отключайте."
        }
        if (usageKnown) {
            val ref = if (a.lastUsed > 0) a.lastUsed else a.installed
            val idle = (now - ref) / DAY
            if (idle > 60) return "Можно удалить" to "Не использовалось более $idle дн."
            if (a.traffic30 > (1L shl 30) && a.fg30 < 10 * 60000L)
                return "Можно удалить" to "Потратило ${Fmt.bytes(a.traffic30)} трафика за 30 дней при почти нулевом времени на экране."
        }
        if (a.cacheBytes > 500L * (1L shl 20))
            return "Проверить" to "Кэш ${Fmt.bytes(a.cacheBytes)}: его можно очистить в настройках приложения."
        if (a.dangerousGranted.size >= 6 && (!usageKnown || a.fg30 < 30 * 60000L))
            return "Проверить" to "Выдано много опасных разрешений (${a.dangerousGranted.size}) при малом использовании."
        if (!usageKnown) return "Проверить" to "Нет доступа к статистике использования, давность использования оценить нельзя."
        return "Нормально" to "Используется, явных проблем не видно."
    }
}
