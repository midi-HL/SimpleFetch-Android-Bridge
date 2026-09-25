package com.deepseekhn.wbtest.protocol

import com.deepseekhn.wbtest.http.HttpExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnState { Disconnected, Handshaking, Connected, Failed }

data class AppStats(
    var requests: Int = 0,
    var success: Int = 0,
    var fail: Int = 0,
    var lastStatus: String = "",
)

/**
 * SimpleFetch 协议引擎：与 AstroBox 插件 event_handler.rs 行为逐字段对应。
 * 手机端无需 host 信封，messageApi 回调拿到的就是快应用直出的 SF JSON。
 */
class SfEngine(
    private val send: (String) -> Unit,
    private val onStateChange: () -> Unit,
) {
    @Volatile var state: ConnState = ConnState.Disconnected
        private set
    @Volatile var failReason: String = ""
        private set
    val stats = AppStats()

    /** 日志回调（UI 展示） */
    var onLog: (String) -> Unit = {}

    private val executor = HttpExecutor()
    private val sseCancels = ConcurrentHashMap<String, AtomicBoolean>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var lastPingAt: Long = 0L
    private var handshakeJob: Job? = null
    private var heartbeatStarted = false

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 3000L
        private const val HEARTBEAT_TIMEOUT_MS = 30_000L
        private const val HANDSHAKE_TIMEOUT_MS = 5000L
    }

    fun ensureHeartbeat() {
        if (heartbeatStarted) return
        heartbeatStarted = true
        scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (state == ConnState.Connected &&
                    System.currentTimeMillis() - lastPingAt > HEARTBEAT_TIMEOUT_MS
                ) {
                    sendJson(
                        JSONObject().apply {
                            put("type", "SF_CLOSE_BRIDGE")
                            put("status", "OK")
                            put("data", JSONObject())
                        }
                    )
                    setState(ConnState.Disconnected)
                }
            }
        }
    }

    /** 用户点击"连接"：主动发握手并启动超时 */
    fun initiateHandshake() {
        setState(ConnState.Handshaking)
        sendJson(
            JSONObject().apply {
                put("type", "SF_HANDSHAKE")
                put("data", JSONObject())
            }
        )
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (state == ConnState.Handshaking) {
                failReason = "握手超时，未收到响应"
                setState(ConnState.Failed)
            }
        }
    }

    /** 用户点击"断开" */
    fun disconnect() {
        handshakeJob?.cancel()
        cancelAllSse()
        if (state == ConnState.Connected) {
            sendJson(
                JSONObject().apply {
                    put("type", "SF_CLOSE_BRIDGE")
                    put("status", "OK")
                    put("data", JSONObject())
                }
            )
        }
        setState(ConnState.Disconnected)
    }

    /** messageApi 回调入口（在任意线程调用均可，内部转到 IO 线程） */
    fun onIncoming(text: String) {
        scope.launch {
            try {
                val root = JSONObject(text)
                val type = root.optString("type", "")
                val data = root.optJSONObject("data")
                when (type) {
                    "SF_HANDSHAKE" -> handleHandshake()
                    "SF_HANDSHAKE_ACK" -> {
                        if (root.optString("status") == "OK") {
                            handshakeJob?.cancel()
                            setState(ConnState.Connected)
                            lastPingAt = System.currentTimeMillis()
                        }
                    }
                    "SF_PING" -> {
                        lastPingAt = System.currentTimeMillis()
                        val ts = data?.opt("ts") ?: 0
                        sendJson(
                            JSONObject().apply {
                                put("type", "SF_PONG")
                                put("status", "OK")
                                put("data", JSONObject().put("ts", ts))
                            }
                        )
                    }
                    "SF_REQUEST" -> {
                        if (isActive()) data?.let { handleRequest(it) }
                    }
                    "SF_DOWNLOAD" -> {
                        if (isActive()) data?.let { handleDownload(it) }
                    }
                    "SF_CLOSE" -> {
                        data?.optString("id")?.let { sseCancels[it]?.set(true) }
                    }
                    "SF_CLOSE_BRIDGE_ACK" -> setState(ConnState.Disconnected)
                }
            } catch (_: Exception) {
                // 非 SF 或坏 JSON，忽略
            }
        }
    }

    private fun isActive(): Boolean =
        state == ConnState.Connected || state == ConnState.Handshaking

    private fun handleHandshake() {
        handshakeJob?.cancel()
        setState(ConnState.Connected)
        lastPingAt = System.currentTimeMillis()
        onLog("收到握手，桥接已连接")
        sendJson(
            JSONObject().apply {
                put("type", "SF_HANDSHAKE_ACK")
                put("status", "OK")
                put("data", JSONObject())
            }
        )
    }

    private fun setState(s: ConnState) {
        state = s
        onStateChange()
    }

    // ============ SF_REQUEST ============
    private suspend fun handleRequest(data: JSONObject) {
        val id = data.optString("id", "")
        val url = data.optString("url", "")
        if (url.isEmpty()) {
            sendError(id, "请求缺少url字段")
            return
        }
        val method = data.optString("method", "GET")
        val isSse = data.optBoolean("sse", false)
        val timeout = data.optLong("timeout", 15000L)
        val headers = parseHeaders(data.optJSONObject("headers"))
        val bodyStr = if (data.isNull("body")) null else data.optString("body")
        val body = bodyStr?.toByteArray(Charsets.UTF_8)

        stats.requests++
        onLog("$method $url")

        if (isSse) {
            runSse(id, method, url, headers, body, timeout)
        } else {
            try {
                val resp = executor.execute(method, url, headers, body, timeout)
                val ok = resp.statusCode in 200..299
                recordResult(ok, "HTTP ${resp.statusCode}")
                onLog("  -> HTTP ${resp.statusCode}，${resp.body.size} 字节")
                val filtered = filterHopByHop(resp.headers)
                sendResponse(id, resp.statusCode, filtered, resp.body)
            } catch (e: Exception) {
                recordResult(false, e.message ?: "")
                val err = classifyError(e.message ?: e.toString())
                onLog("  -> 失败：$err")
                sendError(id, err)
            }
        }
    }

    private suspend fun sendResponse(id: String, statusCode: Int, headers: Map<String, String>, body: ByteArray) {
        // ≤16KB 且合法 UTF-8：单条文本
        if (body.size <= 16 * 1024 && body.isValidUtf8()) {
            sendJson(
                JSONObject().apply {
                    put("type", "SF_RESPONSE")
                    put("status", "OK")
                    put("data", JSONObject().apply {
                        put("id", id)
                        put("statusCode", statusCode)
                        put("headers", JSONObject(headers))
                        put("body", String(body, Charsets.UTF_8))
                        put("chunk", 0)
                        put("totalChunks", 0)
                    })
                }
            )
            return
        }
        // 大数据：整体 base64 后切片。
        val MAX_BODY = 256 * 1024
        val sendBody = if (body.size > MAX_BODY) body.copyOfRange(0, MAX_BODY) else body
        if (sendBody.size < body.size) {
            onLog("  -> 响应过大 ${body.size}B，截断到 ${MAX_BODY / 1024}KB 后回传")
        }
        val encoded = Base64.getEncoder().encodeToString(sendBody)
        val chunkSize = 12 * 1024
        val total = (encoded.length + chunkSize - 1) / chunkSize
        onLog("  -> 分片回传：$total 片（每片 ${chunkSize / 1024}KB，间隔 80ms）")
        for (i in 0 until total) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, encoded.length)
            val chunk = encoded.substring(start, end)
            val chunkNum = i + 1
            sendJson(
                JSONObject().apply {
                    put("type", "SF_RESPONSE")
                    put("status", "OK")
                    put("data", JSONObject().apply {
                        put("id", id)
                        if (chunkNum == 1) {
                            put("statusCode", statusCode)
                            put("headers", JSONObject(headers))
                        }
                        put("body", chunk)
                        put("chunk", chunkNum)
                        put("totalChunks", total)
                    })
                }
            )
            delay(80)
        }
    }

    private fun sendError(id: String, msg: String) {
        sendJson(
            JSONObject().apply {
                put("type", "SF_RESPONSE")
                put("status", msg)
                put("data", JSONObject().apply {
                    put("id", id)
                    put("statusCode", 0)
                    put("error", msg)
                })
            }
        )
    }

    // ============ SSE ============
    private fun runSse(
        id: String, method: String, url: String,
        headers: Map<String, String>, body: ByteArray?, timeout: Long,
    ) {
        val cancelFlag = AtomicBoolean(false)
        sseCancels[id] = cancelFlag
        try {
            executor.executeSse(method, url, headers, body, timeout,
                onEvent = { event, data ->
                    sendJson(
                        JSONObject().apply {
                            put("type", "SF_SSE_EVENT")
                            put("status", "OK")
                            put("data", JSONObject().apply {
                                put("id", id)
                                put("event", if (event.isEmpty()) "message" else event)
                                put("data", data)
                            })
                        }
                    )
                },
                isCancelled = { cancelFlag.get() }
            )
            sendJson(
                JSONObject().apply {
                    put("type", "SF_SSE_END")
                    put("status", "OK")
                    put("data", JSONObject().put("id", id))
                }
            )
            recordResult(true, "SSE完成")
        } catch (e: Exception) {
            if (cancelFlag.get()) {
                sendJson(
                    JSONObject().apply {
                        put("type", "SF_SSE_END")
                        put("status", "OK")
                        put("data", JSONObject().put("id", id))
                    }
                )
            } else {
                recordResult(false, e.message ?: "")
                sendJson(
                    JSONObject().apply {
                        put("type", "SF_SSE_ERROR")
                        put("status", e.message ?: "SSE错误")
                        put("data", JSONObject().put("id", id))
                    }
                )
            }
        } finally {
            sseCancels.remove(id)
        }
    }

    // ============ SF_DOWNLOAD ============
    private fun handleDownload(data: JSONObject) {
        val id = data.optString("id", "")
        val url = data.optString("url", "")
        if (url.isEmpty()) {
            sendDlFail(id, "下载请求缺少url字段")
            return
        }
        val headers = parseHeaders(data.optJSONObject("headers"))
        stats.requests++
        try {
            val resp = executor.execute("GET", url, headers, null, 60000)
            if (resp.statusCode !in 200..299) {
                val msg = "下载失败，HTTP ${resp.statusCode}"
                recordResult(false, msg)
                sendDlFail(id, msg)
                return
            }
            if (resp.body.isEmpty()) {
                recordResult(false, "下载内容为空")
                sendDlFail(id, "下载内容为空")
                return
            }
            val chunkBytes = 8 * 1024
            val total = (resp.body.size + chunkBytes - 1) / chunkBytes
            for (i in 0 until total) {
                val start = i * chunkBytes
                val end = minOf(start + chunkBytes, resp.body.size)
                val chunk = resp.body.copyOfRange(start, end)
                val b64 = Base64.getEncoder().encodeToString(chunk)
                sendJson(
                    JSONObject().apply {
                        put("type", "SF_DL_RESPONSE")
                        put("status", "OK")
                        put("data", JSONObject().apply {
                            put("id", id)
                            put("append", i != 0)
                            put("isLast", i == total - 1)
                            put("data", b64)
                        })
                    }
                )
            }
            recordResult(true, "下载完成")
        } catch (e: Exception) {
            val msg = classifyError(e.message ?: e.toString())
            recordResult(false, msg)
            sendDlFail(id, msg)
        }
    }

    private fun sendDlFail(id: String, msg: String) {
        sendJson(
            JSONObject().apply {
                put("type", "SF_DL_FAIL")
                put("status", msg)
                put("data", JSONObject().apply {
                    put("id", id)
                    put("message", msg)
                })
            }
        )
    }

    private fun cancelAllSse() {
        sseCancels.values.forEach { it.set(true) }
        sseCancels.clear()
    }

    private fun recordResult(ok: Boolean, status: String) {
        if (ok) stats.success++ else stats.fail++
        stats.lastStatus = status
    }

    private fun sendJson(obj: JSONObject) = send(obj.toString())

    // ============ 工具 ============
    private fun parseHeaders(o: JSONObject?): Map<String, String> {
        val m = HashMap<String, String>()
        o ?: return m
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            m[k] = o.optString(k, "")
        }
        return m
    }

    private fun filterHopByHop(headers: Map<String, String>): Map<String, String> {
        val drop = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade",
            "content-encoding", "content-length"
        )
        return headers.filterKeys { it.lowercase() !in drop }
    }

    private fun classifyError(msg: String): String {
        val l = msg.lowercase()
        return when {
            l.contains("timeout") || msg.contains("超时") -> "请求超时"
            l.contains("dns") || l.contains("resolve") -> "DNS解析失败"
            l.contains("refused") || msg.contains("拒绝") -> "连接被拒绝"
            l.contains("unreachable") || l.contains("network") -> "网络不可达"
            else -> "网络错误: $msg"
        }
    }

    private fun ByteArray.isValidUtf8(): Boolean {
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
            decoder.decode(ByteBuffer.wrap(this))
            true
        } catch (_: Exception) {
            false
        }
    }
}
