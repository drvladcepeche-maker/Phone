package com.phoneanalyzer

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/** HTTPS-клиент частного серверного шлюза анализа телефона. */
object ServerBridge {
    const val ENDPOINT = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/phone-analyzer"

    fun ping(context: Context): JSONObject {
        val token = SecureTokenStore.load(context) ?: throw IOException("Сначала сохраните токен моста")
        return post(token, JSONObject().put("action", "ping"))
    }

    fun uploadReport(context: Context, reportJson: String, reportId: String): JSONObject {
        val token = SecureTokenStore.load(context) ?: throw IOException("Сначала сохраните токен моста")
        val report = JSONObject(reportJson)
        val body = JSONObject()
            .put("action", "upload_report")
            .put("client_report_id", reportId)
            .put("report", report)
        return post(token, body)
    }

    private fun post(token: String, payload: JSONObject): JSONObject {
        val raw = payload.toString().toByteArray(Charsets.UTF_8)
        if (raw.size > 25 * 1024 * 1024) throw IOException("Отчёт больше 25 МБ")
        val compressed = ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).use { it.write(raw) }
        }.toByteArray()
        if (compressed.size > 5 * 1024 * 1024) throw IOException("Сжатый отчёт больше 5 МБ")
        var last: IOException? = null
        for (attempt in 1..3) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 120000
                    doOutput = true
                    setFixedLengthStreamingMode(compressed.size)
                    setRequestProperty("Authorization", "Bearer " + token)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Content-Encoding", "gzip")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-store")
                }
                connection.outputStream.use { it.write(compressed) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "{}"
                if (code in 200..299) return JSONObject(response)
                if (code >= 500) throw IOException("Сервер временно недоступен (HTTP $code)")
                val message = try { JSONObject(response).optString("error", response.take(300)) }
                              catch (_: Exception) { response.take(300) }
                throw IllegalStateException("Сервер отклонил запрос (HTTP $code): $message")
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: IOException) {
                last = e
                if (attempt == 3) throw e
                Thread.sleep(attempt * 1500L)
            } finally {
                connection?.disconnect()
            }
        }
        throw last ?: IOException("Не удалось связаться с сервером")
    }
}
