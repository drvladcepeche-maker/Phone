package com.phoneanalyzer

import org.junit.Assert.assertEquals
import org.junit.Test

class RulesTest {
    private fun app(
        pkg: String = "com.example.app",
        system: Boolean = false,
        lastUsed: Long = 0L,
        fg30: Long = 0L,
        wifi: List<Long> = emptyList(),
        mobile: List<Long> = emptyList(),
        cacheBytes: Long = 0L,
        dangerous: List<String> = emptyList(),
        enabled: Boolean = true,
        bootStart: Boolean = false
    ) = AppData(
        pkg = pkg,
        label = pkg,
        version = "1.0",
        versionCode = 1L,
        targetSdk = 34,
        system = system,
        enabled = enabled,
        installer = null,
        installed = 1L,
        updated = 1L,
        appBytes = 0L,
        dataBytes = 0L,
        cacheBytes = cacheBytes,
        lastUsed = lastUsed,
        fg30 = fg30,
        fgDaily = emptyList(),
        wifiDaily = wifi,
        mobileDaily = mobile,
        services = emptyList(),
        fgServices = emptyList(),
        providers = 0,
        bootStart = bootStart,
        netReceiver = false,
        packageReceiver = false,
        requested = emptyList(),
        granted = emptyList(),
        dangerousGranted = dangerous,
        bgLocation = false,
        exactAlarm = false,
        notifListener = false,
        accessibility = false,
        deviceAdmin = false,
        batteryUnrestricted = false
    )

    @Test
    fun missingUsageRecordNeverMeansSafeToDelete() {
        val (category, _) = Rules.classify(app(lastUsed = 0L), now = 100L * 86_400_000L, usageKnown = true)
        assertEquals("Проверить", category)
    }

    @Test
    fun oldUsageRecordRequiresManualReview() {
        val now = 100L * 86_400_000L
        val (category, _) = Rules.classify(app(lastUsed = now - 90L * 86_400_000L), now, usageKnown = true)
        assertEquals("Проверить", category)
    }

    @Test
    fun highTrafficDoesNotRecommendDeletion() {
        val (category, _) = Rules.classify(
            app(lastUsed = 10L, wifi = listOf(2L shl 30), fg30 = 1L),
            now = 100L * 86_400_000L,
            usageKnown = true
        )
        assertEquals("Проверить", category)
    }

    @Test
    fun onlyExplicitlyAllowedSystemPackagesCanBeDisabled() {
        val (allowed, _) = Rules.classify(app(pkg = "com.miui.analytics", system = true), 100L, true)
        val (unknown, _) = Rules.classify(app(pkg = "com.vendor.gamecenter", system = true), 100L, true)
        assertEquals("Можно отключить", allowed)
        assertEquals("Проверить", unknown)
    }

    @Test
    fun criticalSystemPackageIsNeverRecommendedForDisabling() {
        val (category, _) = Rules.classify(app(pkg = "com.android.systemui", system = true), 100L, true)
        assertEquals("Не трогать", category)
    }

    @Test
    fun disabledPackageIsNotSuggestedAgain() {
        val (category, _) = Rules.classify(app(enabled = false), 100L, true)
        assertEquals("Уже отключено", category)
    }
}
