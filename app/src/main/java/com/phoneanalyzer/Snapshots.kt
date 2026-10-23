package com.phoneanalyzer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Локальные снимки состояния (не более 30) и сравнение двух снимков. */
object Snapshots {
    private const val MAX = 30

    private fun dir(ctx: Context): File = File(ctx.filesDir, "snapshots").also { it.mkdirs() }

    fun list(ctx: Context): List<File> =
        (dir(ctx).listFiles { f -> f.name.startsWith("snapshot-") }?.toList() ?: emptyList()).sortedBy { it.name }

    fun save(ctx: Context, apps: List<AppData>): File {
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        for (a in apps) arr.put(JSONObject().apply {
            put("p", a.pkg); put("n", a.label); put("vc", a.versionCode)
            put("cache", a.cacheBytes); put("data", a.dataBytes); put("app", a.appBytes)
            put("traffic", a.traffic30)
            put("g", JSONArray().also { j -> a.granted.forEach { j.put(it) } })
        })
        val f = File(dir(ctx), "snapshot-" + Fmt.stamp(now) + ".json")
        f.writeText(JSONObject().put("time", now).put("apps", arr).toString())
        val all = list(ctx)
        if (all.size > MAX) all.take(all.size - MAX).forEach { it.delete() }
        return f
    }

    private fun index(o: JSONObject): Map<String, JSONObject> {
        val a = o.getJSONArray("apps")
        val m = HashMap<String, JSONObject>()
        for (i in 0 until a.length()) a.getJSONObject(i).let { m[it.getString("p")] = it }
        return m
    }

    fun diff(oldF: File, newF: File): String {
        val o = JSONObject(oldF.readText())
        val n = JSONObject(newF.readText())
        val a = index(o)
        val b = index(n)
        val sb = StringBuilder()
        sb.append("Сравнение: ").append(Fmt.date(o.getLong("time"))).append(" → ").append(Fmt.date(n.getLong("time"))).append("\n\n")
        fun section(title: String, lines: List<String>) {
            if (lines.isEmpty()) return
            sb.append(title).append(" (").append(lines.size).append("):\n")
            lines.take(40).forEach { sb.append("• ").append(it).append("\n") }
            sb.append("\n")
        }
        section("Установлены", b.keys.filter { it !in a }.map { b[it]!!.getString("n") })
        section("Удалены", a.keys.filter { it !in b }.map { a[it]!!.getString("n") })
        section("Обновлены", b.keys.filter { a[it] != null && a[it]!!.getLong("vc") != b[it]!!.getLong("vc") }.map { b[it]!!.getString("n") })
        val mb = 1L shl 20
        section("Вырос кэш (> 50 МБ)", b.keys.filter { a[it] != null && b[it]!!.getLong("cache") - a[it]!!.getLong("cache") > 50 * mb }
            .map { b[it]!!.getString("n") + ": +" + Fmt.bytes(b[it]!!.getLong("cache") - a[it]!!.getLong("cache")) })
        section("Выросли данные (> 100 МБ)", b.keys.filter { a[it] != null && b[it]!!.getLong("data") - a[it]!!.getLong("data") > 100 * mb }
            .map { b[it]!!.getString("n") + ": +" + Fmt.bytes(b[it]!!.getLong("data") - a[it]!!.getLong("data")) })
        section("Вырос трафик за 30 дней (> 100 МБ)", b.keys.filter { a[it] != null && b[it]!!.getLong("traffic") - a[it]!!.getLong("traffic") > 100 * mb }
            .map { b[it]!!.getString("n") + ": +" + Fmt.bytes(b[it]!!.getLong("traffic") - a[it]!!.getLong("traffic")) })
        val perms = ArrayList<String>()
        for (k in b.keys) {
            val oa = a[k] ?: continue
            val before = HashSet<String>()
            val ga = oa.getJSONArray("g")
            for (i in 0 until ga.length()) before.add(ga.getString(i))
            val gb = b[k]!!.getJSONArray("g")
            val added = ArrayList<String>()
            for (i in 0 until gb.length()) if (gb.getString(i) !in before) added.add(gb.getString(i))
            if (added.isNotEmpty()) perms.add(b[k]!!.getString("n") + ": " + added.joinToString(", "))
        }
        section("Получили новые разрешения", perms)
        if (sb.length < 80) sb.append("Существенных изменений нет.")
        return sb.toString()
    }
}
