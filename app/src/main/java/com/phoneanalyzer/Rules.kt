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

    // Только явно известные необязательные предустановленные пакеты.
    // Не классифицируем системные приложения по общим словам вроде "game" или "demo".
    private val optionalPackages = setOf(
        "com.facebook.appmanager", "com.facebook.services", "com.facebook.system",
        "com.google.android.videos", "com.google.android.apps.tachyon",
        "com.google.android.apps.youtube.music", "com.google.android.music",
        "com.google.android.apps.magazines", "com.miui.analytics", "com.miui.msa.global",
        "com.mi.globalbrowser", "com.miui.videoplayer", "com.xiaomi.glgm", "com.xiaomi.mipicks"
    )

    /** Критичные системные пакеты: их нельзя отключать или удалять ни при каких условиях. */
    fun isCritical(p: String): Boolean =
        p == "android" || criticalPrefix.any { p.startsWith(it) } || criticalWords.any { p.contains(it) }

    fun classify(a: AppData, now: Long, usageKnown: Boolean): Pair<String, String> {
        if (!a.enabled) return "Уже отключено" to "Приложение уже отключено в системе."
        val p = a.pkg
        if (a.system) {
            if (p == "android" || criticalPrefix.any { p.startsWith(it) } || criticalWords.any { p.contains(it) })
                return "Не трогать" to "Критичный компонент системы: отключение может нарушить работу телефона."
            if (p in optionalPackages)
                return "Можно отключить" to "Необязательное предустановленное приложение (магазин, облако, игра, соцсеть или реклама). Отключается штатно, телефон работает без него."
            if (a.bootStart && usageKnown && a.fg30 < 5 * 60000L)
                return "Проверить" to "Системное приложение запускается вместе с телефоном, но почти не используется. Перед отключением убедитесь, что оно не нужно."
            return "Проверить" to "Системное приложение неизвестного назначения. Без понимания, зачем оно нужно, не отключайте."
        }
        if (usageKnown) {
            if (a.lastUsed <= 0L) {
                return "Проверить" to "Android не предоставил запись о последнем использовании. Отсутствие записи не доказывает, что приложение давно не использовалось."
            }
            val idle = (now - a.lastUsed) / DAY
            if (idle > 60) {
                return "Проверить" to "Последнее зафиксированное использование было более $idle дн. назад. История Android неполная, поэтому автоматическое предложение удаления небезопасно."
            }
            if (a.traffic30 > (1L shl 30) && a.fg30 < 10 * 60000L)
                return "Проверить" to "Высокий трафик (${Fmt.bytes(a.traffic30)}) при малом времени на экране. Проверьте фоновую работу и назначение приложения."
        }
        if (a.cacheBytes > 500L * (1L shl 20))
            return "Проверить" to "Кэш ${Fmt.bytes(a.cacheBytes)}: его можно очистить в настройках приложения."
        if (a.dangerousGranted.size >= 6 && (!usageKnown || a.fg30 < 30 * 60000L))
            return "Проверить" to "Выдано много опасных разрешений (${a.dangerousGranted.size}) при малом использовании."
        if (!usageKnown) return "Проверить" to "Android не предоставил достоверную запись использования этого приложения; делать вывод о давности использования нельзя."
        return "Нормально" to "Используется, явных проблем не видно."
    }
}
