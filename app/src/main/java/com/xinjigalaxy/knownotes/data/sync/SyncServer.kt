package com.xinjigalaxy.knownotes.data.sync

import com.xinjigalaxy.knownotes.data.model.SyncLogEntry
import com.xinjigalaxy.knownotes.data.media.ImageStore
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/** 默认端口。改端口要两台设备一起改。 */
const val SYNC_DEFAULT_PORT = 8765

private const val MAX_HEADER_BYTES = 16 * 1024
private const val SOCKET_TIMEOUT_MS = 10_000

/**
 * 主机侧的内嵌 HTTP 服务（需求文档 4.4：HTTP + JSON）。
 *
 * 刻意**不引第三方库**（NanoHTTPD / Ktor）：同步只用到两个接口，
 * 为了这点事引一个 Web 框架进来不划算，ServerSocket 手写一层反而更好控。
 *
 * 必须设置共享密钥才能启动 —— 一个监听在 0.0.0.0 上、谁都能读走全部笔记的服务，
 * 比"忘记开同步"更危险。
 */
class SyncServer(
    private val repo: NoteRepository,
    private val engine: SyncEngine,
    private val deviceId: String,
    private val deviceName: String,
    private val imageStore: ImageStore,
) {

    data class Status(
        val running: Boolean = false,
        val port: Int = SYNC_DEFAULT_PORT,
        val servedRequests: Int = 0,
        val lastError: String? = null,
    )

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun start(scope: CoroutineScope, port: Int, key: String): Result<Int> {
        if (_status.value.running) return Result.success(_status.value.port)
        if (key.isBlank()) {
            return Result.failure(IllegalArgumentException("Set a shared key first: a server without a key publishes your notes to the LAN"))
        }
        return runCatching {
            val server = ServerSocket(port)
            server.reuseAddress = true
            serverSocket = server
            _status.value = Status(running = true, port = server.localPort)
            acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server, key) }
            server.localPort
        }.onFailure { e ->
            _status.value = Status(running = false, port = port, lastError = e.message)
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _status.update { it.copy(running = false) }
    }

    private suspend fun CoroutineScope.acceptLoop(server: ServerSocket, key: String) {
        while (isActive) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (!isActive) break
                _status.update { it.copy(lastError = e.message) }
                continue
            }
            launch(Dispatchers.IO) { handle(client, key) }
        }
    }

    private suspend fun handle(client: Socket, key: String) {
        runCatching {
            client.use { sock ->
                sock.soTimeout = SOCKET_TIMEOUT_MS
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                val request = readRequest(input)
                if (request == null) {
                    writeJson(output, 400, errorJson("Malformed request"))
                    return
                }

                if (request.method == "GET" && request.path == "/ping") {
                    writeJson(
                        output,
                        200,
                        JSONObject()
                            .put("protocol", SYNC_PROTOCOL)
                            .put("device_name", deviceName),
                    )
                    return
                }

                if (request.method == "POST" && request.path == "/sync") {
                    val presented = request.headers["x-knownote-key"].orEmpty()
                    if (!secureEquals(presented, key)) {
                        writeJson(output, 401, errorJson("Shared key mismatch"))
                        return
                    }
                    val body = runCatching { JSONObject(request.body) }.getOrNull()
                    if (body == null) {
                        writeJson(output, 400, errorJson("Request body is not valid JSON"))
                        return
                    }
                    if (body.optInt("protocol", 0) != SYNC_PROTOCOL) {
                        writeJson(output, 426, errorJson("Protocol version mismatch; update both devices to the same version"))
                        return
                    }

                    val request_ = SyncRequest.fromJson(body)

                    // 图片：先收下从机认为我缺的，再把我这边它缺的挑出来发回去。
                    // "我缺什么"完全由请求里的 images_i_have 决定 —— 服务端不存任何对端状态。
                    val imagesReceived = engine.applyImages(imageStore, request_.images)
                    val outboundImages = engine.outgoingImages(imageStore, request_.imagesIHave.toSet())
                    // 先发：取「本次请求之前」本机的变更。
                    // 顺序不能反 —— 反了就会把从机这次刚推上来的变更再回声给它自己
                    // （虽然幂等不会出错，但计数会虚高、日志也说谎）。回环测试抓的就是这个。
                    val outbound = engine.collectChanges(request_.lastSyncAt)
                    // 再收：把从机的变更按时间戳规则并进来（本机是主机 → 中转要记变更日志）
                    val applied = engine.applyChanges(request_.notes, originDevice = request_.deviceId)

                    repo.logSync(
                        SyncLogEntry(
                            role = SyncLogEntry.ROLE_HOST,
                            peer = request_.deviceName,
                            pulled = outbound.size,
                            pushed = applied.changed,
                            conflicts = applied.conflicts,
                            ok = true,
                            message = "device ${request_.deviceId.take(8)} connected, " +
                                "images +$imagesReceived/-${outboundImages.size}",
                        )
                    )
                    _status.update { it.copy(servedRequests = it.servedRequests + 1) }

                    writeJson(
                        output,
                        200,
                        SyncResponse(
                            protocol = SYNC_PROTOCOL,
                            deviceId = deviceId,
                            deviceName = deviceName,
                            serverTime = System.currentTimeMillis(),
                            notes = outbound,
                            appliedNotes = applied.changed,
                            conflicts = applied.conflicts,
                            imagesIHave = imageStore.names().toList(),
                            images = outboundImages,
                            imagesReceived = imagesReceived,
                        ).toJson(),
                    )
                    return
                }

                writeJson(output, 404, errorJson("No such endpoint"))
            }
        }.onFailure { e ->
            _status.update { it.copy(lastError = e.message) }
        }
    }

    private class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readRequest(input: InputStream): Request? {
        val headerBytes = readHeaderBlock(input) ?: return null
        val text = String(headerBytes, Charsets.ISO_8859_1)
        val lines = text.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val startLine = lines.first().split(" ")
        if (startLine.size < 2) return null

        val headers = HashMap<String, String>()
        lines.drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return Request(startLine[0].uppercase(), startLine[1], headers, body)
    }

    /** 读到空行（\r\n\r\n）为止，返回整个头块。 */
    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var state = 0
        while (out.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toByteArray()
            out.write(b)
            state = when {
                state == 0 && b == CR -> 1
                state == 1 && b == LF -> 2
                state == 2 && b == CR -> 3
                state == 3 && b == LF -> return out.toByteArray()
                b == CR -> 1
                else -> 0
            }
        }
        return out.toByteArray()
    }

    private fun writeJson(output: OutputStream, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun errorJson(message: String) = JSONObject().put("error", message)

    private fun secureEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private companion object {
        const val CR = '\r'.code
        const val LF = '\n'.code
    }
}

/** 本机在局域网里的 IPv4 地址，同步页要显示出来给另一台设备填。 */
object LanInfo {

    fun ipv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())
}
