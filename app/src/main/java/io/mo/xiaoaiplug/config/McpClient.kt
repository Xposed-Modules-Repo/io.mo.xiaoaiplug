package io.mo.xiaoaiplug.config

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

data class McpTool(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

/** 服务端返回了 JSON-RPC error(区别于网络/协议层的失败)。 */
class McpRpcException(val method: String, val code: Int, message: String) :
    IOException("$method error $code: $message")

/**
 * MCP (Model Context Protocol) 远程客户端。
 * 支持 streamable HTTP 和 SSE 两种传输协议模式。
 *
 * 每次 listTools / callTool 都开一个 [Session]:initialize → notifications/initialized → 请求 → 关闭。
 * 早先的实现每条消息各自裸 POST,有两处让绝大多数服务端拿不到工具:
 * - streamable HTTP:没带回 initialize 时下发的 `Mcp-Session-Id`,有状态的服务端(官方 SDK 默认)
 *   直接 400 "No valid session ID";
 * - SSE:响应是从 GET 事件流推回来的,POST 只回 202,而旧代码读完 endpoint 就把 GET 流关了,
 *   还在 POST 响应体里找 result —— 永远是 "missing result"。
 */
object McpClient {
    private const val TAG = "McpClient"
    private const val PROTOCOL_VERSION = "2025-06-18"
    private val toolCache = ConcurrentHashMap<String, Pair<Long, List<McpTool>>>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 分钟缓存
    // 拉取失败后的冷却:getToolsCached 在小爱进程里同步跑,服务挂了的话每轮都等满超时太伤
    private const val FAIL_BACKOFF_MS = 60 * 1000L

    fun parseHeaders(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (raw.isBlank()) return map
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                for (key in json.keys()) {
                    map[key] = json.optString(key)
                }
                return map
            } catch (_: Throwable) {}
        }
        trimmed.lineSequence().forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val k = line.substring(0, colonIdx).trim()
                val v = line.substring(colonIdx + 1).trim()
                if (k.isNotEmpty()) map[k] = v
            }
        }
        return map
    }

    fun clearCache(serverId: String? = null) {
        if (serverId != null) {
            toolCache.remove(serverId)
            failedAt.remove(serverId)
        } else {
            toolCache.clear()
            failedAt.clear()
        }
    }

    /**
     * 获取缓存的 MCP 工具列表。缓存超时或不存在时同步拉取。
     */
    fun getToolsCached(server: McpServerConfig): List<McpTool> {
        if (!server.enabled) return emptyList()
        val now = System.currentTimeMillis()
        val cached = toolCache[server.id]
        if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
            return cached.second
        }
        failedAt[server.id]?.let { if (now - it < FAIL_BACKOFF_MS) return cached?.second ?: emptyList() }
        return try {
            listTools(server)
        } catch (t: Throwable) {
            failedAt[server.id] = now
            Log.w(TAG, "Failed to fetch tools for ${server.name}: $t")
            cached?.second ?: emptyList()
        }
    }

    /**
     * 向 MCP 服务端发起 tools/list 请求(跟随 nextCursor 分页)，获取可用工具列表。
     */
    fun listTools(server: McpServerConfig, timeoutMs: Int = 10000): List<McpTool> {
        val list = mutableListOf<McpTool>()
        Session.open(server, timeoutMs).use { session ->
            var cursor: String? = null
            var pages = 0
            do {
                val params = JSONObject()
                cursor?.let { params.put("cursor", it) }
                val result = session.request("tools/list", params)
                val toolsArr = result.optJSONArray("tools") ?: JSONArray()
                for (i in 0 until toolsArr.length()) {
                    val item = toolsArr.optJSONObject(i) ?: continue
                    val name = item.optString("name")
                    val desc = item.optString("description", "")
                    val schema = item.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")
                    if (name.isNotEmpty()) {
                        list.add(McpTool(server.id, server.name, name, desc, schema))
                    }
                }
                cursor = if (result.isNull("nextCursor")) null
                else result.optString("nextCursor").ifEmpty { null }
            } while (cursor != null && ++pages < 20)
        }

        toolCache[server.id] = System.currentTimeMillis() to list
        failedAt.remove(server.id)
        return list
    }

    /**
     * 执行 MCP 工具 tools/call。
     */
    fun callTool(server: McpServerConfig, toolName: String, args: JSONObject, timeoutMs: Int = 30000): String {
        val result = try {
            Session.open(server, minOf(timeoutMs, 10000)).use { session ->
                session.request(
                    "tools/call",
                    JSONObject().put("name", toolName).put("arguments", args),
                    timeoutMs
                )
            }
        } catch (e: McpRpcException) {
            return "error: MCP tool call failed: ${e.message}"
        }

        val sb = StringBuilder()
        val contentArr = result.optJSONArray("content")
        if (contentArr != null) {
            for (i in 0 until contentArr.length()) {
                val item = contentArr.optJSONObject(i) ?: continue
                val text = when {
                    item.has("text") -> item.optString("text")
                    item.optString("type") == "resource" ->
                        item.optJSONObject("resource")?.optString("text").orEmpty()
                    else -> "[${item.optString("type", "content")}]"
                }
                if (text.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(text)
            }
        }
        if (sb.isEmpty()) {
            sb.append(result.optJSONObject("structuredContent")?.toString() ?: result.toString())
        }
        return if (result.optBoolean("isError")) "error: $sb" else sb.toString()
    }

    /**
     * 一次 MCP 会话。streamable HTTP 下负责带回 `Mcp-Session-Id` / `MCP-Protocol-Version`，
     * 并从 JSON 或 SSE 响应体里挑出与请求 id 对应的那条；SSE 传输下 GET 事件流全程保持，
     * 后台线程按 id 把响应分发给等待中的请求。
     */
    private class Session private constructor(
        private val server: McpServerConfig,
        private val timeoutMs: Int
    ) : Closeable {
        private val headers = parseHeaders(server.headers)
        private val isSse = server.transportType == McpServerConfig.TRANSPORT_SSE
        private val nextId = AtomicInteger(1)
        @Volatile private var sessionId: String? = null
        @Volatile private var protocolVersion: String? = null
        private var postUrl: String = server.url

        private var sseConn: HttpURLConnection? = null
        private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
        @Volatile private var sseError: Throwable? = null
        @Volatile private var closed = false

        companion object {
            fun open(server: McpServerConfig, timeoutMs: Int): Session {
                val s = Session(server, timeoutMs)
                try {
                    if (s.isSse) s.openSseStream()
                    s.initialize()
                } catch (t: Throwable) {
                    s.close()
                    throw t
                }
                return s
            }
        }

        private fun initialize() {
            val result = request("initialize", JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "XiaoAiPlug")
                    put("version", "1.0")
                })
            })
            protocolVersion = result.optString("protocolVersion").ifEmpty { null }
            try {
                post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), timeoutMs, null)
            } catch (t: Throwable) {
                Log.w(TAG, "notifications/initialized failed for ${server.name}: $t")
            }
        }

        fun request(method: String, params: JSONObject, timeout: Int = timeoutMs): JSONObject {
            val id = nextId.getAndIncrement()
            val msg = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params)

            val resp = if (isSse) {
                val future = CompletableFuture<JSONObject>()
                pending[id.toString()] = future
                try {
                    sseError?.let { throw IOException("SSE 连接已断开: ${it.message}", it) }
                    // 个别服务端会直接在 POST 响应体里回结果，那就不用等事件流了
                    post(msg, timeout, id) ?: future.get(timeout.toLong(), TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    throw IOException("$method 等待 SSE 响应超时 (${timeout}ms)")
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                } finally {
                    pending.remove(id.toString())
                }
            } else {
                post(msg, timeout, id) ?: throw IOException("$method: 服务端未返回响应")
            }

            resp.optJSONObject("error")?.let {
                throw McpRpcException(method, it.optInt("code"), it.optString("message"))
            }
            return resp.optJSONObject("result")
                ?: throw IOException("$method 响应缺少 result: ${resp.toString().take(300)}")
        }

        /**
         * POST 一条消息。返回与 [expectId] 匹配的响应(JSON 体或 SSE 流里的那条)；
         * 通知、202 Accepted、或 SSE 传输下响应走事件流时返回 null。
         */
        private fun post(msg: JSONObject, timeout: Int, expectId: Int?): JSONObject? {
            val conn = URL(postUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = timeout
                conn.readTimeout = timeout
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json, text/event-stream")
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
                protocolVersion?.let { conn.setRequestProperty("MCP-Protocol-Version", it) }

                conn.outputStream.use { it.write(msg.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code >= 400) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IOException("HTTP $code: ${err.take(500)}")
                }
                conn.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }

                if (expectId == null || code == 202 || code == 204) return null
                val contentType = conn.contentType.orEmpty().lowercase()
                val reader = conn.inputStream.bufferedReader()
                return reader.use {
                    if (contentType.contains("text/event-stream")) {
                        var found: JSONObject? = null
                        readSse(it) { _, data ->
                            found = parseMessages(data).firstOrNull { m -> isResponseTo(m, expectId) }
                            found != null
                        }
                        found ?: if (isSse) null else throw IOException("SSE 响应流结束但未收到 id=$expectId 的响应")
                    } else {
                        val body = it.readText()
                        val matched = parseMessages(body).firstOrNull { m -> isResponseTo(m, expectId) }
                        if (matched == null && !isSse && body.isNotBlank()) {
                            throw IOException("无法解析的响应 (HTTP $code, $contentType): ${body.take(300)}")
                        }
                        matched
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

        /** SSE 传输: GET 建立事件流，等 endpoint 事件拿到 POST 地址，之后流保持打开接收响应。 */
        private fun openSseStream() {
            val conn = URL(server.url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = 0 // 长连接；结束靠 close() 里的 disconnect
            conn.setRequestProperty("Accept", "text/event-stream")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            sseConn = conn

            val code = conn.responseCode
            if (code >= 400) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("SSE GET HTTP $code: ${err.take(500)}")
            }

            val endpoint = CompletableFuture<String>()
            thread(isDaemon = true, name = "mcp-sse-${server.name}") {
                try {
                    conn.inputStream.bufferedReader().use { reader ->
                        readSse(reader) { event, data ->
                            if (event == "endpoint" ||
                                (event.isEmpty() && !endpoint.isDone && !data.trimStart().startsWith("{"))
                            ) {
                                endpoint.complete(data.trim())
                            } else {
                                for (m in parseMessages(data)) {
                                    val id = m.opt("id")?.toString() ?: continue
                                    if (m.has("result") || m.has("error")) pending[id]?.complete(m)
                                }
                            }
                            closed
                        }
                    }
                    if (!closed) throw IOException("SSE 连接被服务端关闭")
                } catch (t: Throwable) {
                    if (!closed) {
                        sseError = t
                        endpoint.completeExceptionally(t)
                        pending.values.forEach { it.completeExceptionally(t) }
                    }
                }
            }

            val path = try {
                endpoint.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw IOException("SSE 未返回 endpoint 事件 (${server.url})")
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
            postUrl = URI(server.url).resolve(path).toString()
        }

        override fun close() {
            closed = true
            sseConn?.let { c -> try { c.disconnect() } catch (_: Throwable) {} }
            sseConn = null
            // streamable HTTP 有状态会话：DELETE 让服务端及时释放(失败无所谓)
            val sid = sessionId
            if (!isSse && sid != null) {
                try {
                    val c = URL(postUrl).openConnection() as HttpURLConnection
                    c.requestMethod = "DELETE"
                    c.connectTimeout = 2000
                    c.readTimeout = 2000
                    for ((k, v) in headers) c.setRequestProperty(k, v)
                    c.setRequestProperty("Mcp-Session-Id", sid)
                    c.responseCode
                    c.disconnect()
                } catch (_: Throwable) {}
            }
        }

        private fun isResponseTo(m: JSONObject, id: Int): Boolean =
            m.opt("id")?.toString() == id.toString() && (m.has("result") || m.has("error"))

        /** 消息体可能是单个 JSON-RPC 对象，也可能是批量数组；不是 JSON 就返回空。 */
        private fun parseMessages(raw: String): List<JSONObject> {
            val s = raw.trim()
            return try {
                when {
                    s.startsWith("{") -> listOf(JSONObject(s))
                    s.startsWith("[") -> JSONArray(s).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                    else -> emptyList()
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }

        /** 最小 SSE 解析：多行 data 拼接，空行派发；[onEvent] 返回 true 即停止读取。 */
        private fun readSse(reader: BufferedReader, onEvent: (event: String, data: String) -> Boolean) {
            var event = ""
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine()
                if (line == null || line.isEmpty()) {
                    if (data.isNotEmpty()) {
                        if (onEvent(event, data.toString())) return
                    }
                    event = ""
                    data.setLength(0)
                    if (line == null) return
                    continue
                }
                if (line.startsWith(":")) continue
                val colon = line.indexOf(':')
                val field = if (colon < 0) line else line.substring(0, colon)
                var value = if (colon < 0) "" else line.substring(colon + 1)
                if (value.startsWith(" ")) value = value.substring(1)
                when (field) {
                    "event" -> event = value
                    "data" -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(value)
                    }
                }
            }
        }
    }
}
