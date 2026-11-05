package com.phoneanalyzer

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Безопасный адаптер Shizuku.
 * Принимает только фиксированные операции pm и проверенные имена пакетов.
 * Произвольные shell-строки не исполняются.
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

    fun request(): Boolean {
        return try {
            if (!running()) false
            else if (granted()) true
            else { Shizuku.requestPermission(REQ); true }
        } catch (_: Throwable) { false }
    }

    private val packageNamePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    fun validPkg(pkg: String): Boolean = packageNamePattern.matches(pkg)

    private fun newProcess(args: Array<String>): Process {
        // Shizuku API exposes this helper as a private static method in 13.1.x.
        // Reflection is confined here; all arguments are constructed from the allowlist below.
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, args, null, null) as Process
    }

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

        val process = newProcess(args)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { process.inputStream.bufferedReader().use { stdout.append(it.readText()) } }
        val errThread = Thread { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
        outThread.start()
        errThread.start()
        val code = process.waitFor()
        outThread.join(5000)
        errThread.join(5000)
        return code to (stdout.toString() + "\n" + stderr.toString()).trim().take(8000)
    }

    fun succeeded(result: Pair<Int, String>): Boolean =
        result.first == 0 &&
            !result.second.contains("Failure", ignoreCase = true) &&
            !result.second.contains("Error", ignoreCase = true) &&
            !result.second.contains("Exception", ignoreCase = true)

    /**
     * Read-only system AppOps history. This is intentionally a fixed command:
     * no caller-provided shell text or package arguments are accepted.
     */
    fun execAppOpsDump(): Pair<Int, String> {
        require(ready()) { "Shizuku не запущен или разрешение не выдано" }
        val process = newProcess(arrayOf("dumpsys", "appops"))
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (stdout.length < 4 * 1024 * 1024) stdout.append(line).append('\\n')
                }
            }
        }
        val errThread = Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                for (line in lines) if (stderr.length < 16000) stderr.append(line).append('\\n')
            }
        }
        outThread.start()
        errThread.start()
        val code = process.waitFor()
        outThread.join(10000)
        errThread.join(10000)
        return code to (stdout.toString() + "\\n" + stderr.toString()).take(4 * 1024 * 1024)
    }
}
