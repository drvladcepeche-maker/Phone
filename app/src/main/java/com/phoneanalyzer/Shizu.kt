package com.phoneanalyzer

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Обёртка над Shizuku: выполнение команд pm с правами adb (без root). */
object Shizu {
    const val PKG = "moe.shizuku.privileged.api"
    const val REQ = 1001

    fun installed(ctx: Context): Boolean =
        try { ctx.packageManager.getPackageInfo(PKG, 0); true } catch (e: Exception) { false }

    fun running(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun granted(): Boolean =
        try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (e: Throwable) { false }

    fun ready(): Boolean = running() && granted()

    fun request() { try { Shizuku.requestPermission(REQ) } catch (e: Throwable) { } }

    private val okName = Regex("^[A-Za-z0-9_.]+$")

    /** Имя пакета должно состоять только из безопасных символов, иначе команда не выполняется. */
    fun validPkg(p: String): Boolean = p.isNotEmpty() && okName.matches(p)

    /** Выполняет команду оболочки через Shizuku. Возвращает код выхода и вывод. */
    fun exec(cmd: String): Pair<Int, String> {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd + " 2>&1"), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        return code to out.trim()
    }

    fun succeeded(r: Pair<Int, String>): Boolean =
        r.first == 0 && !r.second.contains("Failure") && !r.second.contains("Error") && !r.second.contains("Exception")
}
