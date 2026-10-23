package com.phoneanalyzer

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import org.json.JSONObject
import java.util.Calendar

/** Сбор данных о телефоне и приложениях. Вызывать из фонового потока. */
@Suppress("DEPRECATION")
class Collector(private val ctx: Context) {
    private val pm = ctx.packageManager
    private val day = 24L * 3600 * 1000
    private val days = 30

    /** Признаки того, какие источники данных оказались доступны. */
    var networkOk = true
    var mobileNetworkOk = true
    var storageOk = true

    fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun device(): JSONObject {
        val o = JSONObject()
        o.put("model", Build.MANUFACTURER + " " + Build.MODEL)
        o.put("android", Build.VERSION.RELEASE)
        o.put("sdk", Build.VERSION.SDK_INT)
        o.put("securityPatch", Build.VERSION.SECURITY_PATCH)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        o.put("ramTotal", mi.totalMem)
        o.put("ramAvailable", mi.availMem)
        o.put("ramLowThreshold", mi.threshold)
        o.put("ramLow", mi.lowMemory)
        val st = StatFs(Environment.getDataDirectory().path)
        o.put("storageTotal", st.totalBytes)
        o.put("storageFree", st.availableBytes)
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (b != null) {
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            o.put("batteryPercent", if (level >= 0 && scale > 0) level * 100 / scale else -1)
            o.put("batteryTempC", b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            o.put("batteryStatus", b.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
            o.put("batteryHealth", b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1))
            o.put("batteryPlugged", b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
        }
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        o.put("batteryChargeCounterUah", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
        return o
    }

    private fun listPackages(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        else pm.getInstalledPackages(0)

    private fun info(pkg: String, flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageInfo(pkg, flags)
    } catch (e: Exception) { null }

    private fun receiverPkgs(action: String, data: Uri? = null): Set<String> = try {
        val i = Intent(action)
        if (data != null) i.data = data
        val l = if (Build.VERSION.SDK_INT >= 33) pm.queryBroadcastReceivers(i, PackageManager.ResolveInfoFlags.of(0L))
        else pm.queryBroadcastReceivers(i, 0)
        l.mapNotNull { it.activityInfo?.packageName }.toSet()
    } catch (e: Exception) { emptySet() }

    private fun enabledPkgs(key: String): Set<String> {
        val s = Settings.Secure.getString(ctx.contentResolver, key) ?: return emptySet()
        return s.split(":").mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()
    }

    private fun midnight(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun netDaily(nsm: NetworkStatsManager, type: Int, start0: Long): HashMap<Int, LongArray> {
        val m = HashMap<Int, LongArray>()
        val bucket = NetworkStats.Bucket()
        for (i in 0 until days) {
            val s = start0 + i * day
            val st = nsm.querySummary(type, null, s, s + day)
            try {
                while (st.hasNextBucket()) {
                    st.getNextBucket(bucket)
                    m.getOrPut(bucket.uid) { LongArray(days) }[i] += bucket.rxBytes + bucket.txBytes
                }
            } finally { st.close() }
        }
        return m
    }

    private fun short(p: String) = p.removePrefix("android.permission.")

    /** Собирает все приложения и присваивает им категории. */
    fun collect(progress: (Int, Int) -> Unit = { _, _ -> }): List<AppData> {
        val now = System.currentTimeMillis()
        val usage = hasUsageAccess()
        val start0 = midnight() - (days - 1) * day

        val lastUsed = HashMap<String, Long>()
        val fg30 = HashMap<String, Long>()
        val fgDaily = HashMap<String, LongArray>()
        if (usage) {
            try {
                val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                for ((k, v) in usm.queryAndAggregateUsageStats(now - 365 * day, now)) lastUsed[k] = v.lastTimeUsed
                for ((k, v) in usm.queryAndAggregateUsageStats(now - 30 * day, now)) fg30[k] = v.totalTimeInForeground
                for (s in usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start0, now)) {
                    val idx = ((s.firstTimeStamp - start0) / day).toInt().coerceIn(0, days - 1)
                    fgDaily.getOrPut(s.packageName) { LongArray(days) }[idx] += s.totalTimeInForeground
                }
            } catch (e: Exception) { /* статистика недоступна */ }
        }

        var wifi = HashMap<Int, LongArray>()
        var mobile = HashMap<Int, LongArray>()
        if (usage) {
            val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
            try { wifi = netDaily(nsm, ConnectivityManager.TYPE_WIFI, start0) } catch (e: Exception) { networkOk = false }
            if (Build.VERSION.SDK_INT >= 29) {
                try { mobile = netDaily(nsm, ConnectivityManager.TYPE_MOBILE, start0) } catch (e: Exception) { mobileNetworkOk = false }
            } else mobileNetworkOk = false
        } else { networkOk = false; mobileNetworkOk = false }

        val boot = receiverPkgs(Intent.ACTION_BOOT_COMPLETED)
        val net = receiverPkgs("android.net.conn.CONNECTIVITY_CHANGE")
        val pkgEv = receiverPkgs(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:com.example.x"))
        val notif = enabledPkgs("enabled_notification_listeners")
        val acc = enabledPkgs("enabled_accessibility_services")
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admins: Set<String> = try { dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet() } catch (e: Exception) { emptySet() }
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val dangerCache = HashMap<String, Boolean>()
        fun dangerous(name: String): Boolean = dangerCache.getOrPut(name) {
            try {
                val pi = pm.getPermissionInfo(name, 0)
                (pi.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
            } catch (e: Exception) { false }
        }

        val base = listPackages()
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS
        val out = ArrayList<AppData>()
        for ((n, bp) in base.withIndex()) {
            progress(n, base.size)
            val pi = info(bp.packageName, flags) ?: bp
            val ai = pi.applicationInfo ?: continue
            val pkg = pi.packageName
            val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { pkg }
            val system = (ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            val installer: String? = try {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName else pm.getInstallerPackageName(pkg)
            } catch (e: Exception) { null }
            val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            var app = -1L; var data = -1L; var cache = -1L
            if (usage) {
                try {
                    val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                    app = s.appBytes; data = s.dataBytes; cache = s.cacheBytes
                } catch (e: Exception) { storageOk = false }
            }
            val services = pi.services?.map { it.name } ?: emptyList()
            val fgServices = pi.services?.filter {
                Build.VERSION.SDK_INT >= 29 && it.foregroundServiceType != 0
            }?.map { it.name } ?: emptyList()
            val req = pi.requestedPermissions?.toList() ?: emptyList()
            val rf = pi.requestedPermissionsFlags
            val granted = ArrayList<String>()
            for (i in req.indices) if (rf != null && i < rf.size && (rf[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) granted.add(req[i])
            val danger = granted.filter { dangerous(it) }.map { short(it) }
            val raw = AppData(
                pkg = pkg, label = label, version = pi.versionName ?: "", versionCode = vc,
                targetSdk = ai.targetSdkVersion, system = system, enabled = ai.enabled, installer = installer,
                installed = pi.firstInstallTime, updated = pi.lastUpdateTime,
                appBytes = app, dataBytes = data, cacheBytes = cache,
                lastUsed = lastUsed[pkg] ?: 0L, fg30 = fg30[pkg] ?: 0L,
                fgDaily = (fgDaily[pkg] ?: LongArray(days)).toList(),
                wifiDaily = (wifi[ai.uid] ?: LongArray(days)).toList(),
                mobileDaily = (mobile[ai.uid] ?: LongArray(days)).toList(),
                services = services, fgServices = fgServices, providers = pi.providers?.size ?: 0,
                bootStart = pkg in boot, netReceiver = pkg in net, packageReceiver = pkg in pkgEv,
                requested = req.map { short(it) }, granted = granted.map { short(it) }, dangerousGranted = danger,
                bgLocation = granted.contains("android.permission.ACCESS_BACKGROUND_LOCATION"),
                exactAlarm = req.contains("android.permission.SCHEDULE_EXACT_ALARM") || req.contains("android.permission.USE_EXACT_ALARM"),
                notifListener = pkg in notif, accessibility = pkg in acc, deviceAdmin = pkg in admins,
                batteryUnrestricted = try { power.isIgnoringBatteryOptimizations(pkg) } catch (e: Exception) { false }
            )
            val (cat, why) = Rules.classify(raw, now, usage)
            out.add(raw.copy(category = cat, reason = why))
        }
        progress(base.size, base.size)
        return out
    }
}
