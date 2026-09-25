package com.deepseekhn.wbtest.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** 普通 HTTP 结果（与 Rust api_client::HttpResponse 对应） */
data class HttpResult(
    val statusCode: Int,
    /** 响应头，key 已小写，hop-by-hop 尚未过滤（由引擎过滤） */
    val headers: Map<String, String>,
    val body: ByteArray,
)

class HttpExecutor {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // 不开启 OkHttp 透明 gzip，保持与 Rust 一致的手动解压
        .build()

    /**
     * 执行普通 HTTP 请求。成功返回 HttpResult；网络层错误抛出异常（由引擎分类为中文文案）。
     */
    fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Long,
    ): HttpResult {
        val client = client.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val builder = Request.Builder().url(url)

        // 带 body 但未声明 Content-Type 时自动补（与 Rust 一致）
        var finalHeaders = headers
        if (body != null && body.isNotEmpty() &&
            headers.keys.none { it.equals("content-type", ignoreCase = true) }
        ) {
            val first = body.firstOrNull { it != ' '.code.toByte() && it != '\t'.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() }
            val ct = if (first == '{'.code.toByte() || first == '['.code.toByte())
                "application/json;charset=UTF-8"
            else
                "application/x-www-form-urlencoded"
            finalHeaders = headers + ("Content-Type" to ct)
        }

        for ((k, v) in finalHeaders) {
            builder.header(k, v)
        }

        val bodyMedia = when (method.uppercase()) {
            "GET", "HEAD" -> null
            else -> body ?: ByteArray(0)
        }
        val okBody = bodyMedia?.toRequestBody(null)
        when (method.uppercase()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post(okBody ?: ByteArray(0).toRequestBody(null))
            "PUT" -> builder.put(okBody ?: ByteArray(0).toRequestBody(null))
            "DELETE" -> builder.delete(okBody)
            "PATCH" -> builder.patch(okBody ?: ByteArray(0).toRequestBody(null))
            else -> builder.method(method.uppercase(), okBody)
        }

        client.newCall(builder.build()).execute().use { resp ->
            val respHeaders = HashMap<String, String>()
            for ((k, v) in resp.headers) {
                respHeaders[k.lowercase()] = v
            }
            val raw = resp.body?.bytes() ?: ByteArray(0)
            val decompressed = decompressIfNeeded(raw, respHeaders)
            return HttpResult(
                statusCode = resp.code,
                headers = respHeaders,
                body = decompressed,
            )
        }
    }

    /**
     * 执行 SSE 流式请求。逐事件回调 onEvent(event, data)；返回时通过 onEnd/onError 收尾。
     * isCancelled 用于响应 SF_CLOSE。
     */
    fun executeSse(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Long,
        onEvent: (event: String, data: String) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val client = client.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            // SSE 是长连接，读超时给长一点，但每个事件间隙仍需可取消
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val builder = Request.Builder().url(url)
            .header("Accept", "text/event-stream")
        for ((k, v) in headers) builder.header(k, v)

        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: ByteArray(0)).toRequestBody(null))
            else -> builder.method(method.uppercase(), body?.toRequestBody(null))
        }

        client.newCall(builder.build()).execute().use { resp ->
            if (!(resp.code in 200..299)) {
                throw RuntimeException("SSE 连接失败，状态码: ${resp.code}")
            }
            val source = resp.body!!.source()
            val buffer = StringBuilder()
            var pendingEvent = "message"
            while (!source.exhausted()) {
                if (isCancelled()) return
                val line = source.readUtf8Line() ?: break
                buffer.append(line).append('\n')
                if (line == "") {
                    // 事件边界
                    val block = buffer.toString()
                    buffer.setLength(0)
                    val evt = parseEventBlock(block, pendingEvent)
                    pendingEvent = "message"
                    if (evt != null) {
                        onEvent(evt.first, evt.second)
                    }
                } else if (line.startsWith("event:")) {
                    pendingEvent = line.removePrefix("event:").trim()
                }
            }
        }
    }

    /** 解析一个 SSE 事件块，返回 (event, data)；无 data 行返回 null */
    private fun parseEventBlock(block: String, currentEvent: String): Pair<String, String>? {
        val dataLines = ArrayList<String>()
        var event = currentEvent
        for (raw in block.split('\n')) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trimStart())
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            }
        }
        if (dataLines.isEmpty()) return null
        return event to dataLines.joinToString("\n")
    }

    /** 与 Rust decompress_if_needed 一致：gzip 魔数或 content-encoding: deflate 时解压 */
    private fun decompressIfNeeded(body: ByteArray, headers: Map<String, String>): ByteArray {
        val enc = headers["content-encoding"] ?: ""
        if (body.size >= 2 && body[0] == 0x1f.toByte() && body[1] == 0x8b.toByte()) {
            try {
                GZIPInputStream(ByteArrayInputStream(body)).use { gz ->
                    return gz.readBytes()
                }
            } catch (_: Exception) {
            }
        }
        if (enc.contains("deflate")) {
            try {
                InflaterInputStream(ByteArrayInputStream(body), Inflater(true)).use { inf ->
                    return inf.readBytes()
                }
            } catch (_: Exception) {
            }
        }
        return body
    }
}
