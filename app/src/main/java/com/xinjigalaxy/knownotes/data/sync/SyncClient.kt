package com.xinjigalaxy.knownotes.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从机侧的客户端：一次性 POST /sync（先推自己的变更，再收主机的变更）。
 *
 * 用 HttpURLConnection 而不是引 OkHttp：一次请求、无连接池需求，系统自带的够用。
 */
class SyncClient(
    private val deviceId: String,
    private val deviceName: String,
) {

    data class Outcome(
        val response: SyncResponse? = null,
        val error: String? = null,
    ) {
        val ok: Boolean get() = response != null
    }

    suspend fun ping(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open(host, port, "/ping", "GET")
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        }.getOrDefault(false)
    }

    suspend fun sync(
        host: String,
        port: Int,
        key: String,
        lastSyncAt: Long,
        notes: List<SyncNote>,
        imagesIHave: List<String> = emptyList(),
        images: List<SyncImage> = emptyList(),
    ): Outcome = withContext(Dispatchers.IO) {
        runCatching {
            val payload = SyncRequest(
                deviceId = deviceId,
                deviceName = deviceName,
                lastSyncAt = lastSyncAt,
                notes = notes,
                imagesIHave = imagesIHave,
                images = images,
            ).toJson().toString()

            val conn = open(host, port, "/sync", "POST")
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-KnowNote-Key", key)
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            conn.disconnect()

            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(text).optString("error")
                }.getOrNull().orEmpty().ifBlank { "HTTP $code" }
                Outcome(error = message)
            } else {
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json == null) {
                    Outcome(error = "Host returned invalid JSON")
                } else {
                    val response = SyncResponse.fromJson(json)
                    if (response.protocol != SYNC_PROTOCOL) {
                        Outcome(error = "Protocol mismatch (host expects $SYNC_PROTOCOL, got ${response.protocol})")
                    } else {
                        Outcome(response = response)
                    }
                }
            }
        }.getOrElse { e ->
            Outcome(error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun open(host: String, port: Int, path: String, method: String): HttpURLConnection =
        (URL("http://$host:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            useCaches = false
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 15_000
    }
}

/**
 * 把用户填的地址拆成 (host, port)。
 *
 * 允许「192.168.1.5」「192.168.1.5:8765」「http://192.168.1.5:8765」三种写法 ——
 * 让用户去记端口格式是没必要的心智负担。
 */
fun parseSyncAddress(input: String, defaultPort: Int = SYNC_DEFAULT_PORT): Pair<String, Int>? {
    var text = input.trim()
    if (text.isEmpty()) return null
    text = text.removePrefix("http://").removePrefix("https://").trimEnd('/')
    if (text.isEmpty()) return null
    val colon = text.lastIndexOf(':')
    return if (colon > 0 && text.indexOf(']') < colon) {
        val host = text.substring(0, colon)
        val port = text.substring(colon + 1).toIntOrNull()
        if (port == null || port !in 1..65535) null else host to port
    } else {
        text to defaultPort
    }
}
