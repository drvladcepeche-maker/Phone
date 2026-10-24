package com.phoneanalyzer

import android.app.Activity
import android.app.AlertDialog
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private lateinit var collector: Collector
    private var apps: List<AppData> = emptyList()
    private var device: JSONObject = JSONObject()
    private var usage = false
    private var loading = false
    private var current = 0
    private var filterIdx = 0
    private var sortIdx = 0

    private val filters = listOf("Все", "Пользовательские", "Системные", "Давно не используемые", "Удалить или отключить")
    private val sorts = listOf("По имени", "По месту (данные+кэш)", "По трафику", "По времени на экране")
    private val reqJson = 31
    private val reqTxt = 32

    private var pendingText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collector = Collector(applicationContext)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Сводка", "Приложения", "Изменения", "Отчёт", "Очистка").forEachIndexed { i, t ->
            nav.addView(Button(this).apply {
                text = t; textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { if (i == 4) startActivity(Intent(this@MainActivity, CleanupActivity::class.java)) else show(i) }
            })
        }
        content = FrameLayout(this)
        root.addView(nav)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        usage = collector.hasUsageAccess()
        if (!usage) askUsageAccess()
        reload()
        show(0)
    }

    override fun onResume() {
        super.onResume()
        val now = collector.hasUsageAccess()
        if (now != usage) { usage = now; reload() }
    }

    private fun askUsageAccess() {
        AlertDialog.Builder(this).setTitle("Нужен доступ к статистике")
            .setMessage("Чтобы показать время работы приложений, трафик и размеры данных, Android требует специальный доступ «Статистика использования». " +
                "Приложение только читает эти данные и по умолчанию не отправляет их никуда. " +
                "Нажмите «Открыть настройки», найдите «Анализ телефона» и включите доступ. Без него данные будут неполными.")
            .setPositiveButton("Открыть настройки") { _, _ -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .setNegativeButton("Позже", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun bg(work: () -> Unit) = Thread {
        try { work() } catch (e: Throwable) { runOnUiThread { toast("Ошибка: " + e.message) } }
    }.start()

    private fun reload() {
        if (loading) return
        loading = true
        show(current)
        bg {
            try {
                collector = Collector(applicationContext)
                val d = collector.device()
                val a = collector.collect()
                runOnUiThread { device = d; apps = a; loading = false; show(current) }
            } finally {
                runOnUiThread { loading = false }
            }
        }
    }

    private fun show(i: Int) {
        current = i
        content.removeAllViews()
        if (loading) {
            content.addView(ProgressBar(this), FrameLayout.LayoutParams(160, 160, android.view.Gravity.CENTER))
            return
        }
        when (i) {
            0 -> content.addView(scroll(summaryView()))
            1 -> content.addView(appsView())
            2 -> content.addView(scroll(changesView()))
            else -> content.addView(scroll(reportView()))
        }
    }

    private fun scroll(v: View) = ScrollView(this).apply { addView(v) }

    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }

    private fun tv(s: String, size: Float = 14f) = TextView(this).apply { text = s; textSize = size; setTextIsSelectable(true); setPadding(0, 8, 0, 8) }

    private fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }

    // ---------- Сводка ----------

    private fun summaryView(): View {
        val c = col()
        if (apps.isEmpty()) { c.addView(tv("Данные ещё не собраны.")); c.addView(btn("Обновить") { reload() }); return c }
        val d = device
        val user = apps.count { !it.system }
        val sb = StringBuilder()
        sb.append(d.optString("model")).append("\nAndroid ").append(d.optString("android")).append(", патч ").append(d.optString("securityPatch")).append("\n\n")
        sb.append("ОЗУ: свободно ").append(Fmt.bytes(d.optLong("ramAvailable"))).append(" из ").append(Fmt.bytes(d.optLong("ramTotal")))
            .append(if (d.optBoolean("ramLow")) "  (мало памяти!)" else "").append("\n")
        sb.append("Хранилище: свободно ").append(Fmt.bytes(d.optLong("storageFree"))).append(" из ").append(Fmt.bytes(d.optLong("storageTotal"))).append("\n")
        sb.append("Батарея: ").append(d.optInt("batteryPercent")).append("%, ").append(d.optDouble("batteryTempC")).append(" °C\n")
        sb.append("Приложений: ").append(apps.size).append(" (пользовательских ").append(user).append(", системных ").append(apps.size - user).append(")\n\n")
        sb.append("Рекомендации:\n")
        apps.groupBy { it.category }.entries.sortedBy { it.key }.forEach { sb.append("• ").append(it.key).append(": ").append(it.value.size).append("\n") }
        c.addView(tv(sb.toString(), 15f))
        if (!usage) {
            c.addView(tv("Нет доступа к статистике использования: время работы, трафик и размеры данных недоступны."))
            c.addView(btn("Выдать доступ") { askUsageAccess() })
        }
        c.addView(tv("Время и трафик берутся из системной статистики; расход батареи и процессора по чужим приложениям Android не раскрывает, поэтому оценки приблизительные.", 12f))
        c.addView(btn("Обновить данные") { reload() })
        return c
    }

    // ---------- Приложения ----------

    private fun filtered(): List<AppData> {
        val now = System.currentTimeMillis()
        val day = 24L * 3600 * 1000
        var l = when (filterIdx) {
            1 -> apps.filter { !it.system }
            2 -> apps.filter { it.system }
            3 -> apps.filter { val ref = if (it.lastUsed > 0) it.lastUsed else it.installed; (now - ref) / day > 60 }
            4 -> apps.filter { it.category == "Можно удалить" || it.category == "Можно отключить" }
            else -> apps
        }
        l = when (sortIdx) {
            1 -> l.sortedByDescending { it.storage }
            2 -> l.sortedByDescending { it.traffic30 }
            3 -> l.sortedByDescending { it.fg30 }
            else -> l.sortedBy { it.label.lowercase() }
        }
        return l
    }

    private fun appsView(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 0) }
        val list = ListView(this)
        val info = TextView(this)
        fun fill() {
            val l = filtered()
            info.text = "Показано: " + l.size + " из " + apps.size + ". Нажмите на приложение для подробностей."
            list.adapter = object : ArrayAdapter<AppData>(this@MainActivity, android.R.layout.simple_list_item_2, l) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                    val a = getItem(position)!!
                    v.findViewById<TextView>(android.R.id.text1).text = a.label + "  [" + a.category + "]"
                    v.findViewById<TextView>(android.R.id.text2).text = "место " + Fmt.bytes(a.storage) + " • кэш " + Fmt.bytes(a.cacheBytes) +
                        " • трафик " + Fmt.bytes(a.traffic30) + " • экран " + Fmt.time(a.fg30) + " • исп. " + Fmt.date(a.lastUsed)
                    return v
                }
            }
            list.setOnItemClickListener { _, _, pos, _ -> detail(l[pos]) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fb = Button(this).apply { textSize = 11f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val sbn = Button(this).apply { textSize = 11f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        fb.text = "Фильтр: " + filters[filterIdx]
        sbn.text = "Сорт.: " + sorts[sortIdx]
        fb.setOnClickListener { filterIdx = (filterIdx + 1) % filters.size; fb.text = "Фильтр: " + filters[filterIdx]; fill() }
        sbn.setOnClickListener { sortIdx = (sortIdx + 1) % sorts.size; sbn.text = "Сорт.: " + sorts[sortIdx]; fill() }
        row.addView(fb); row.addView(sbn)
        box.addView(row); box.addView(info); box.addView(list)
        fill()
        return box
    }

    private fun detail(a: AppData) {
        val sb = StringBuilder()
        sb.append(a.pkg).append("\nВерсия ").append(a.version).append(", targetSdk ").append(a.targetSdk)
            .append(if (a.system) ", системное" else ", пользовательское").append(if (a.enabled) "" else ", отключено").append("\n")
        sb.append("Источник установки: ").append(a.installer ?: "н/д").append("\n")
        sb.append("Установлено ").append(Fmt.date(a.installed)).append(", обновлено ").append(Fmt.date(a.updated)).append("\n")
        sb.append("Приложение ").append(Fmt.bytes(a.appBytes)).append(", данные ").append(Fmt.bytes(a.dataBytes)).append(", кэш ").append(Fmt.bytes(a.cacheBytes)).append("\n")
        sb.append("На экране: 7 дн ").append(Fmt.time(a.fg7)).append(", 30 дн ").append(Fmt.time(a.fg30)).append("; последнее использование ").append(Fmt.date(a.lastUsed)).append("\n")
        sb.append("Трафик 30 дн: Wi-Fi ").append(Fmt.bytes(a.wifiDaily.sum())).append(", моб. ").append(Fmt.bytes(a.mobileDaily.sum())).append("\n")
        sb.append("Автозапуск: ").append(if (a.bootStart) "да" else "нет").append("; службы: ").append(a.services.size)
            .append(" (foreground: ").append(a.fgServices.size).append(")\n")
        sb.append("Опасные разрешения выданы (").append(a.dangerousGranted.size).append("): ").append(a.dangerousGranted.joinToString(", ").ifEmpty { "нет" }).append("\n")
        if (a.bgLocation) sb.append("Фоновое местоположение: да\n")
        if (a.accessibility) sb.append("Служба спец. возможностей: да\n")
        if (a.notifListener) sb.append("Доступ к уведомлениям: да\n")
        if (a.deviceAdmin) sb.append("Администратор устройства: да\n")
        if (a.batteryUnrestricted) sb.append("Исключено из оптимизации батареи\n")
        sb.append("\n").append(a.category).append(": ").append(a.reason)
        val tvv = TextView(this).apply { text = sb.toString(); setTextIsSelectable(true); setPadding(48, 24, 48, 0); textSize = 13f }
        AlertDialog.Builder(this).setTitle(a.label)
            .setView(ScrollView(this).apply { addView(tvv) })
            .setPositiveButton("Удалить") { _, _ ->
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)))
            }
            .setNeutralButton("Настройки") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)))
            }
            .setNegativeButton("Закрыть", null).show()
    }

    // ---------- Изменения ----------

    private fun changesView(): View {
        val c = col()
        val snaps = Snapshots.list(this)
        c.addView(btn("Сделать снимок сейчас") {
            if (apps.isEmpty()) { toast("Данные ещё не собраны"); return@btn }
            Snapshots.save(this, apps); toast("Снимок сохранён"); show(2)
        })
        c.addView(tv("Снимков сохранено: " + snaps.size + " (хранятся последние 30)."))
        if (snaps.size < 2) c.addView(tv("Для сравнения нужно минимум два снимка."))
        else c.addView(tv(Snapshots.diff(snaps[snaps.size - 2], snaps.last())))
        return c
    }

    // ---------- Отчёт и настройки ----------

    private fun reportView(): View {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val c = col()
        c.addView(tv("Отчёт содержит сводку устройства и данные по приложениям. Он не содержит сообщений, звонков, контактов, файлов, истории браузера, местоположения и идентификаторов устройства.", 13f))
        c.addView(btn("Сохранить отчёт (JSON)") { withReport { json, _ -> pendingText = json; createDoc("report.json", "application/json", reqJson) } })
        c.addView(btn("Сохранить отчёт (текст)") { withReport { _, txt -> pendingText = txt; createDoc("report.txt", "text/plain", reqTxt) } })
        c.addView(btn("Поделиться текстом") {
            withReport { _, txt ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, txt.take(100000))
                }, "Поделиться отчётом"))
            }
        })
        c.addView(tv("Отправка в репозиторий (по умолчанию выключена)", 15f))
        val enable = CheckBox(this).apply { text = "Включить отправку отчёта"; isChecked = prefs.getBoolean("upload", false) }
        val repo = EditText(this).apply { hint = "Репозиторий (владелец/имя)"; setSingleLine(); setText(prefs.getString("repo", "drvladcepeche-maker/Phone")) }
        val token = EditText(this).apply {
            hint = "GitHub-токен (Contents: Read and write)"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("token", ""))
        }
        val daily = CheckBox(this).apply { text = "Делать снимок раз в сутки"; isChecked = prefs.getBoolean("daily", false) }
        listOf<View>(enable, repo, token, daily).forEach { c.addView(it) }
        c.addView(btn("Сохранить настройки") {
            prefs.edit().putBoolean("upload", enable.isChecked).putString("repo", repo.text.toString().trim())
                .putString("token", token.text.toString().trim()).putBoolean("daily", daily.isChecked).apply()
            schedule(daily.isChecked)
            toast("Настройки сохранены")
        })
        c.addView(btn("Отправить отчёт в репозиторий") {
            if (!prefs.getBoolean("upload", false)) { toast("Включите отправку и сохраните настройки"); return@btn }
            val t = prefs.getString("token", "") ?: ""
            val r = prefs.getString("repo", "") ?: ""
            if (t.isEmpty() || r.isEmpty()) { toast("Укажите репозиторий и токен"); return@btn }
            withReport { json, _ -> confirmUpload(t, r, json) }
        })
        return c
    }

    private fun schedule(on: Boolean) {
        val js = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (on) js.schedule(JobInfo.Builder(1, ComponentName(this, SnapshotJob::class.java))
            .setPeriodic(24L * 3600 * 1000).build())
        else js.cancel(1)
    }

    private fun withReport(action: (String, String) -> Unit) {
        if (apps.isEmpty()) { toast("Данные ещё не собраны"); return }
        bg {
            val json = Reporter.json(device, apps, usage, collector)
            val txt = Reporter.text(device, apps, usage)
            runOnUiThread { action(json, txt) }
        }
    }

    private fun confirmUpload(token: String, repo: String, json: String) {
        val name = "report-" + Fmt.stamp(System.currentTimeMillis()) + ".json.gz"
        val data = Reporter.gzip(json)
        AlertDialog.Builder(this).setTitle("Отправить отчёт?")
            .setMessage("Приложений: " + apps.size + "\nРазмер: " + Fmt.bytes(json.toByteArray(Charsets.UTF_8).size.toLong()) + " (в архиве gzip: " + Fmt.bytes(data.size.toLong()) + ")" +
                "\nРазделы: сводка устройства; по каждому приложению — версия, размеры, время на экране, трафик по дням, службы, автозапуск, разрешения, категория и причина." +
                "\n\nКуда: " + repo + "/reports/" + name + "\nТокен в отчёт не входит. Файл можно сначала сохранить и просмотреть.")
            .setPositiveButton("Отправить") { _, _ ->
                toast("Отправка…")
                bg {
                    val url = Reporter.upload(token, repo, name, data) { n -> runOnUiThread { toast("Повтор, попытка " + n + " из 3…") } }
                    runOnUiThread { AlertDialog.Builder(this).setTitle("Отправлено").setMessage(url).setPositiveButton("Закрыть", null).show() }
                }
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun createDoc(name: String, mime: String, code: Int) {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_TITLE, name)
        }, code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        val uri = data?.data ?: return
        if (result != RESULT_OK || (code != reqJson && code != reqTxt)) return
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(pendingText.toByteArray(Charsets.UTF_8)) }
            toast("Файл сохранён")
        } catch (e: Exception) { toast("Ошибка: " + e.message) }
    }
}
