package mumu.xsy.mumuchat.tools.mcp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.util.concurrent.atomic.AtomicLong

class McpHttpClient(
    private val http: OkHttpClient,
    private val endpoint: String,
    private val authToken: String?
) {
    private val gson = Gson()
    private val ids = AtomicLong(1L)

    @Volatile
    private var initialized: Boolean = false

    fun isInitialized(): Boolean = initialized

    fun initializeIfNeeded() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val initResult = call(
                method = "initialize",
                params = JsonObject().apply {
                    addProperty("protocolVersion", "2025-06-18")
                    add("capabilities", JsonObject().apply {
                        add("roots", JsonObject().apply { addProperty("listChanged", false) })
                        add("sampling", JsonObject())
                    })
                    add("clientInfo", JsonObject().apply {
                        addProperty("name", "MuMuChat")
                        addProperty("version", "2.0")
                    })
                }
            )
            if (!initResult.isJsonObject) throw McpException("init_failed", "initialize 返回格式错误")
            notify(method = "notifications/initialized", params = null)
            initialized = true
        }
    }

    fun listTools(): JsonObject {
        initializeIfNeeded()
        val result = call(method = "tools/list", params = JsonObject())
        return result.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().apply { add("raw", result) }
    }

    fun callTool(name: String, arguments: JsonObject?): JsonObject {
        initializeIfNeeded()
        val result = call(
            method = "tools/call",
            params = JsonObject().apply {
                addProperty("name", name)
                add("arguments", arguments ?: JsonObject())
            }
        )
        return result.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().apply { add("raw", result) }
    }

    private fun notify(method: String, params: JsonObject?) {
        val msg = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        val req = buildPostRequest(gson.toJson(msg))
        http.newCall(req).execute().use { resp ->
            if (resp.code != 200 && resp.code != 202) {
                throw McpException("http_error", "HTTP ${resp.code}")
            }
        }
    }

    private fun call(method: String, params: JsonObject?): JsonElement {
        val id = ids.getAndIncrement()
        val msg = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        val req = buildPostRequest(gson.toJson(msg))
        http.newCall(req).execute().use { resp ->
            val ct = resp.header("Content-Type").orEmpty()
            val body = resp.body ?: throw McpException("no_body", "响应体为空")
            return if (ct.startsWith("text/event-stream")) {
                parseSseForId(body.source(), id)
            } else {
                val text = body.string()
                val obj = JsonParser.parseString(text).asJsonObject
                val err = obj.getAsJsonObject("error")
                if (err != null) {
                    val code = err.get("code")?.asString ?: "rpc_error"
                    val message = err.get("message")?.asString ?: "rpc_error"
                    throw McpException(code, message)
                }
                val result = obj.get("result")
                result ?: JsonObject()
            }
        }
    }

    private fun buildPostRequest(json: String): Request {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(endpoint)
            .post(body)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
        if (!authToken.isNullOrBlank()) builder.header("Authorization", "Bearer ${authToken.trim()}")
        return builder.build()
    }

    private fun parseSseForId(source: BufferedSource, id: Long): JsonElement {
        val buf = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) {
                if (buf.isNotEmpty()) {
                    val data = buf.toString().trim()
                    buf.setLength(0)
                    if (data == "[DONE]") continue
                    val obj = JsonParser.parseString(data).asJsonObject
                    val msgId = obj.get("id")?.asLong
                    if (msgId == id) {
                        val err = obj.getAsJsonObject("error")
                        if (err != null) {
                            val code = err.get("code")?.asString ?: "rpc_error"
                            val message = err.get("message")?.asString ?: "rpc_error"
                            throw McpException(code, message)
                        }
                        return obj.get("result") ?: JsonObject()
                    }
                }
                continue
            }
            if (line.startsWith("data:", ignoreCase = true)) {
                buf.append(line.substringAfter("data:", "").trim())
            }
        }
        throw McpException("no_response", "未收到响应")
    }
}

