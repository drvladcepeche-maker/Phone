package com.phoneanalyzer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.net.HttpURLConnection
import java.net.URL

/**
 * Очистка: удаление, отключение приложений и очистка кэша.
 * Без Shizuku каждое действие подтверждает сам Android (системные окна и страницы настроек).
 * С Shizuku действия выполняются одной кнопкой. Всё запускается только после вашего подтверждения.
 */
class CleanupActivity : Activity() {

    private class Item(
        val pkg: String, val label: String, val action: String, val system: Boolean,
        val detail: String, var checked: Boolean = false
    )

    private lateinit var holder: FrameLayout
    private var apps: List<AppData> = emptyList()
    private var items = mutableListOf<Item>()
    private var loading = true
    private var busy = false
    private var planNote = ""
    private val queue = ArrayDeque<Item>()
    private var currentItem: Item? = null
    private val results = mutableListOf<String>()
    private val reqSys = 77

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Очистка"
        holder = FrameLayout(this)
        setContentView(holder)
        try { Shizuku.addRequestPermissionResultListener(permListener) } catch (e: Throwable) { }
        reload()
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(permListener) } catch (e: Throwable) { }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!loading && !busy) render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun bg(work: () -> Unit) = Thread {
        try { work() } catch (e: Throwable) { runOnUiThread { busy = false; toast("Ошибка: " + e.message); render() } }
    }.start()

    // ---------- Данные ----------

    private fun homePkg(): String? = try {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    } catch (e: Exception) { null }

    private fun isProtected(a: AppData): Boolean =
        a.pkg == packageName || a.pkg == Shizu.PKG || a.pkg == homePkg() || (a.system && Rules.isCritical(a.pkg))

    private fun reload() {
        loading = true
        render()
        bg {
            val a = Collector(applicationContext).collect()
            val checked = items.filter { it.checked }.map { it.pkg + "|" + it.action }.toSet()
            val list = mutableListOf<Item>()
            for (x in a) {
                if (isProtected(x)) continue
                if (x.category == "Можно удалить" && !x.system)
                    list.add(Item(x.pkg, x.label, "uninstall", false, Fmt.bytes(x.storage) + ". " + x.reason))
                else if (x.category == "Можно отключить" && x.system)
                    list.add(Item(x.pkg, x.label, "disable", true, Fmt.bytes(x.storage) + ". " + x.reason))
            }
            val inList = list.map { it.pkg }.toSet()
            for (x in a.filter { it.enabled && it.cacheBytes >= 100L * (1L shl 20) && it.pkg !in inList && !isProtected(it) }
                .sortedByDescending { it.cacheBytes })
                list.add(Item(x.pkg, x.label, "cache", x.system, "Кэш " + Fmt.bytes(x.cacheBytes)))
            list.forEach { it.checked = (it.pkg + "|" + it.action) in checked }
            runOnUiThread { apps = a; items = list; loading = false; render() }
        }
    }

    // ---------- Интерфейс ----------

    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
    private fun tv(s: String, size: Float = 14f) = TextView(this).apply { text = s; textSize = size; setPadding(0, 8, 0, 8) }
    private fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }

    private fun render() {
        holder.removeAllViews()
        if (loading || busy) {
            val c = col()
            c.addView(ProgressBar(this))
            if (busy) c.addView(tv("Выполняется…"))
            holder.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            return
        }
        val c = col()
        c.addView(tv("Очистка телефона", 20f))
        c.addView(tv("Отметьте приложения и нажмите «Выполнить». Ничего не запускается без вашего подтверждения. Критичные системные приложения в список не попадают."))
        shizukuBlock(c)
        planBlock(c)

        listOf(
            Triple("uninstall", "Удалить (давно не используются)", "Удаление пользовательских приложений необратимо."),
            Triple("disable", "Отключить (предустановленные)", "Отключённое приложение можно включить обратно ниже."),
            Triple("cache", "Очистить кэш", "Данные и вход в аккаунты сохраняются, удаляются только временные файлы.")
        ).forEach { (act, head, note) ->
            val group = items.filter { it.action == act }
            if (group.isEmpty()) return@forEach
            c.addView(tv(head + " — " + group.size, 17f))
            c.addView(tv(note, 12f))
            c.addView(btn("Выбрать всё / снять в этой группе") {
                val all = group.all { it.checked }
                group.forEach { it.checked = !all }
                render()
            })
            group.forEach { it0 ->
                c.addView(CheckBox(this).apply {
                    text = it0.label + "\n" + it0.detail
                    textSize = 14f
                    isChecked = it0.checked
                    setOnCheckedChangeListener { _, v -> it0.checked = v }
                })
            }
        }
        if (items.isEmpty()) c.addView(tv("Кандидатов на очистку нет."))

        val n = items.count { it.checked }
        c.addView(btn("Выполнить выбранное ($n)") { confirmRun() })

        val disabled = apps.filter { !it.enabled && it.pkg != packageName }
        if (disabled.isNotEmpty()) {
            c.addView(tv("Отключённые приложения — " + disabled.size, 17f))
            c.addView(tv("«Включить» возвращает приложение в работу.", 12f))
            disabled.forEach { a ->
                c.addView(btn("Включить: " + a.label) { enableApp(a) })
            }
        }
        c.addView(tv("История действий", 17f))
        c.addView(tv(historyText(), 12f))
        holder.addView(ScrollView(this).apply { addView(c) })
    }

    private fun shizukuBlock(c: LinearLayout) {
        c.addView(tv("Режим работы", 17f))
        when {
            Shizu.ready() -> c.addView(tv("Shizuku подключён: действия выполняются одной кнопкой, без окон Android."))
            !Shizu.installed(this) -> {
                c.addView(tv("Без Shizuku: для каждого приложения Android покажет своё окно или страницу настроек, подтверждаете вы сами. Чтобы отключать предустановленные приложения и чистить кэш одной кнопкой, установите Shizuku."))
                c.addView(btn("Скачать Shizuku") { openUrl("https://shizuku.rikka.app/download/") })
            }
            !Shizu.running() -> {
                c.addView(tv("Shizuku установлен, но не запущен. Откройте его и выберите «Запуск через беспроводную отладку» (нужен Wi-Fi и включённая отладка по Wi-Fi в параметрах разработчика). Запускать заново нужно после перезагрузки телефона."))
                c.addView(btn("Открыть Shizuku") {
                    val i = packageManager.getLaunchIntentForPackage(Shizu.PKG)
                    if (i != null) startActivity(i) else toast("Не удалось открыть Shizuku")
                })
            }
            else -> {
                c.addView(tv("Shizuku запущен. Разрешите этому приложению пользоваться им."))
                c.addView(btn("Дать разрешение Shizuku") { Shizu.request() })
            }
        }
    }

    private fun planBlock(c: LinearLayout) {
        c.addView(tv("План из репозитория", 17f))
        c.addView(tv("Загружает plans/cleanup.json из репозитория (из настроек вкладки «Отчёт») и только отмечает галочки. Выполнение всё равно требует вашего подтверждения.", 12f))
        c.addView(btn("Загрузить план") { loadPlan() })
        if (planNote.isNotEmpty()) c.addView(tv(planNote, 13f))
    }

    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { toast("Не удалось открыть ссылку") }
    }

    // ---------- План ----------

    private fun loadPlan() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val repo = prefs.getString("repo", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (repo.isEmpty() || token.isEmpty()) { toast("Укажите репозиторий и токен во вкладке «Отчёт» и сохраните настройки"); return }
        bg {
            val conn = URL("https://api.github.com/repos/" + repo + "/contents/plans/cleanup.json").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 30000; conn.readTimeout = 60000
                conn.setRequestProperty("Authorization", "Bearer " + token)
                conn.setRequestProperty("Accept", "application/vnd.github.raw+json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                val code = conn.responseCode
                if (code == 404) { runOnUiThread { toast("Плана нет: plans/cleanup.json не найден") }; return@bg }
                if (code !in 200..299) { runOnUiThread { toast("Не удалось загрузить план: HTTP " + code) }; return@bg }
                val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                runOnUiThread { applyPlan(JSONObject(text)) }
            } finally { conn.disconnect() }
        }
    }

    private fun applyPlan(plan: JSONObject) {
        var marked = 0
        var skipped = 0
        for (act in listOf("uninstall", "disable", "cache")) {
            val arr: JSONArray = plan.optJSONArray(act) ?: continue
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i)
                val a = apps.find { it.pkg == pkg }
                if (a == null || !Shizu.validPkg(pkg) || isProtected(a)) { skipped++; continue }
                val ex = items.find { it.pkg == pkg && it.action == act }
                if (ex != null) { ex.checked = true; marked++ }
                else { items.add(Item(pkg, a.label, act, a.system, "Из плана. " + Fmt.bytes(a.storage)).also { it.checked = true }); marked++ }
            }
        }
        planNote = "План загружен" + (if (plan.optString("note").isNotEmpty()) ": " + plan.optString("note") else "") +
            "\nОтмечено: " + marked + ", пропущено: " + skipped + " (нет на телефоне или защищено)."
        render()
    }

    // ---------- Выполнение ----------

    private fun actionName(a: String) = when (a) { "uninstall" -> "удалить"; "disable" -> "отключить"; else -> "очистить кэш" }

    private fun confirmRun() {
        val sel = items.filter { it.checked }
        if (sel.isEmpty()) { toast("Ничего не отмечено"); return }
        val msg = sel.take(20).joinToString("\n") { "• " + actionName(it.action) + ": " + it.label } +
            (if (sel.size > 20) "\n…и ещё " + (sel.size - 20) else "") +
            (if (sel.any { it.action == "uninstall" }) "\n\nУдалённые приложения вернуть нельзя (кроме повторной установки)." else "") +
            (if (Shizu.ready()) "" else "\n\nДалее Android будет показывать окна подтверждения по одному.")
        AlertDialog.Builder(this).setTitle("Выполнить " + sel.size + " действий?").setMessage(msg)
            .setPositiveButton("Выполнить") { _, _ -> run(sel) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun run(sel: List<Item>) {
        results.clear()
        if (Shizu.ready()) runShizuku(sel) else {
            queue.clear(); queue.addAll(sel); next()
        }
    }

    private fun cmdFor(it: Item): String? {
        if (!Shizu.validPkg(it.pkg)) return null
        return when (it.action) {
            "uninstall" -> if (it.system) "pm uninstall --user 0 " + it.pkg else "pm uninstall " + it.pkg
            "disable" -> "pm disable-user --user 0 " + it.pkg
            "cache" -> "pm clear --cache-only " + it.pkg
            else -> null
        }
    }

    private fun runShizuku(sel: List<Item>) {
        busy = true; render()
        bg {
            for (it in sel) {
                val cmd = cmdFor(it)
                if (cmd == null) { results.add("✗ " + it.label + ": недопустимое имя пакета"); continue }
                val r = try { Shizu.exec(cmd) } catch (e: Throwable) { -1 to (e.message ?: "ошибка") }
                val ok = Shizu.succeeded(r)
                log(it, ok, r.second.take(120))
                results.add((if (ok) "✓ " else "✗ ") + actionName(it.action) + ": " + it.label + (if (ok) "" else " (" + r.second.take(80) + ")"))
                it.checked = false
            }
            runOnUiThread { busy = false; showResults(); reload() }
        }
    }

    private fun next() {
        val it = queue.removeFirstOrNull()
        if (it == null) { currentItem = null; showResults(); reload(); return }
        currentItem = it
        val intent = if (it.action == "uninstall")
            Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + it.pkg)).putExtra(Intent.EXTRA_RETURN_RESULT, true)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + it.pkg))
        if (it.action == "disable") toast(it.label + ": нажмите «Отключить», затем вернитесь назад")
        if (it.action == "cache") toast(it.label + ": «Хранилище» → «Очистить кэш», затем вернитесь назад")
        try { startActivityForResult(intent, reqSys) } catch (e: Exception) {
            results.add("✗ " + it.label + ": не удалось открыть окно"); next()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqSys) return
        val it = currentItem
        if (it != null) {
            if (it.action == "uninstall") {
                val ok = resultCode == RESULT_OK
                log(it, ok, if (ok) "удалено" else "отменено")
                results.add((if (ok) "✓ удалено: " else "– отменено: ") + it.label)
            } else {
                log(it, true, "открыто в настройках")
                results.add("→ " + actionName(it.action) + " (в настройках): " + it.label)
            }
            it.checked = false
        }
        next()
    }

    private fun showResults() {
        if (results.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Готово").setMessage(results.joinToString("\n"))
            .setPositiveButton("Закрыть", null).show()
    }

    private fun enableApp(a: AppData) {
        if (Shizu.ready() && Shizu.validPkg(a.pkg)) {
            busy = true; render()
            bg {
                val r = try { Shizu.exec("pm enable --user 0 " + a.pkg) } catch (e: Throwable) { -1 to (e.message ?: "ошибка") }
                val ok = Shizu.succeeded(r)
                log(Item(a.pkg, a.label, "enable", a.system, ""), ok, r.second.take(120))
                runOnUiThread { busy = false; toast(if (ok) "Включено: " + a.label else "Не удалось: " + r.second.take(80)); reload() }
            }
        } else {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))) } catch (e: Exception) { toast("Не удалось открыть настройки") }
        }
    }

    // ---------- Журнал ----------

    private fun log(it: Item, ok: Boolean, msg: String) {
        val p = getSharedPreferences("cleanup", Context.MODE_PRIVATE)
        val arr = try { JSONArray(p.getString("log", "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(JSONObject().put("t", System.currentTimeMillis()).put("pkg", it.pkg).put("name", it.label)
            .put("action", it.action).put("ok", ok).put("msg", msg))
        val out = JSONArray()
        for (i in maxOf(0, arr.length() - 100) until arr.length()) out.put(arr.get(i))
        p.edit().putString("log", out.toString()).apply()
    }

    private fun historyText(): String {
        val p = getSharedPreferences("cleanup", Context.MODE_PRIVATE)
        val arr = try { JSONArray(p.getString("log", "[]")) } catch (e: Exception) { JSONArray() }
        if (arr.length() == 0) return "Пока пусто."
        val sb = StringBuilder()
        for (i in arr.length() - 1 downTo maxOf(0, arr.length() - 15)) {
            val o = arr.getJSONObject(i)
            val a = when (o.optString("action")) { "uninstall" -> "удалено"; "disable" -> "отключено"; "cache" -> "кэш очищен"; "enable" -> "включено"; else -> o.optString("action") }
            sb.append(Fmt.stamp(o.optLong("t"))).append(" ").append(if (o.optBoolean("ok")) "✓ " else "✗ ")
                .append(a).append(": ").append(o.optString("name")).append("\n")
        }
        return sb.toString()
    }
}
