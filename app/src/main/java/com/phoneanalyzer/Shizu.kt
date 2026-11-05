package com.phoneanalyzer

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Безопасный адаптер Shizuku.
 *
 * Команды не принимаются в виде произвольной shell-строки: только фиксированный
 * набор операций pm и проверенное имя пакета. Это исключает shell injection.
 */
object Shizu {
    const val PKG = "moe.shizuku.privileged.api"
    const val REQ = 1001

    fun installed(ctx: Context): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ctx.packageManager.getPackageInfo(PKG, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(PKG, 0)
        }
        true
    } catch (_: Exception) { false }

    fun running(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun granted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun ready(): Boolean = running() && granted()

    fun request(): Boolean = try {
        if (!running()) return false
        if (granted()) return true
        Shizuku.requestPermission(REQ)
        true
    } catch (_: Throwable) { false }

    private val packageNamePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    fun validPkg(pkg: String): Boolean = packageNamePattern.matches(pkg)

    /**
     * Исполняются только команды из allowlist. Имя пакета передаётся отдельным
     * аргументом процесса, никогда не склеивается с shell-командой.
     */
    fun execPm(operation: String, pkg: String, system: Boolean = false): Pair<Int, String> {
        require(ready()) { "Shizuku не запущен или разрешение не выдано" }
        require(validPkg(pkg)) { "Недопустимое имя пакета" }

        val args = when (operation) {
            "disable" -> arrayOf("pm", "disable-user", "--user", "0", pkg)
            "enable" -> arrayOf("pm", "enable", "--user", "0", pkg)
            "cache" -> arrayOf("pm", "clear", "--cache-only", pkg)
            "uninstall" -> if (system) arrayOf("pm", "uninstall", "--user", "0", pkg)
                           else arrayOf("pm", "uninstall", pkg)
            else -> throw IllegalArgumentException("Операция не разрешена")
        }

        val process = Shizuku.newProcess(args, null, null)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread {
            process.inputStream.bufferedReader().use { stdout.append(it.readText()) }
        }
        val errThread = Thread {
            process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
        }
        outThread.start()
        errThread.start()
        val code = process.waitFor()
        outThread.join(5000)
        errThread.join(5000)
        val output = (stdout.toString() + "\n" + stderr.toString()).trim().take(8000)
        return code to output
    }

    fun succeeded(result: Pair<Int, String>): Boolean =
        result.first == 0 &&
            !result.second.contains("Failure", ignoreCase = true) &&
            !result.second.contains("Error", ignoreCase = true) &&
            !result.second.contains("Exception", ignoreCase = true)
}
