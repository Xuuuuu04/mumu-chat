package mumu.xsy.mumuchat

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("mumu_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    var settings by mutableStateOf(loadSettings())
        private set

    var sessions = mutableStateListOf<ChatSession>()
    var currentSessionId by mutableStateOf<String?>(null)
    var inputDraft by mutableStateOf("")
    var selectedImageUri by mutableStateOf<Uri?>(null)

    private var currentGenerationJob: Job? = null
    private var currentEventSource: EventSource? = null

    val currentMessages: List<ChatMessage>
        get() = sessions.find { it.id == currentSessionId }?.messages ?: emptyList()

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        val firstSession = ChatSession(title = "新对话")
        sessions.add(firstSession)
        currentSessionId = firstSession.id
    }

    private fun loadSettings(): AppSettings {
        val json = prefs.getString("settings", null)
        return try { gson.fromJson(json, AppSettings::class.java) ?: AppSettings() } catch (e: Exception) { AppSettings() }
    }

    private fun saveSettings(newSettings: AppSettings) {
        settings = newSettings
        prefs.edit().putString("settings", gson.toJson(newSettings)).apply()
    }

    fun updateSettings(newSettings: AppSettings) { saveSettings(newSettings) }

    fun addMemory(fact: String) = saveSettings(settings.copy(memories = settings.memories + fact))
    fun deleteMemory(index: Int) {
        val updated = settings.memories.toMutableList().apply { if(index in indices) removeAt(index) }
        saveSettings(settings.copy(memories = updated))
    }
    fun updateMemory(index: Int, text: String) {
        val updated = settings.memories.toMutableList().apply { if(index in indices) this[index] = text }
        saveSettings(settings.copy(memories = updated))
    }

    fun isVisionModel() = settings.selectedModel.lowercase().run {
        contains("vl") || contains("gemini") || contains("vision") || contains("omni") || contains("glm") || contains("step") || contains("ocr")
    }

    fun fetchAvailableModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("${settings.baseUrl}/models").header("Authorization", "Bearer ${settings.apiKey}").get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val ids = gson.fromJson(response.body?.string(), JsonObject::class.java).getAsJsonArray("data").map { it.asJsonObject.get("id").asString }.sorted()
                        launch(Dispatchers.Main) { saveSettings(settings.copy(fetchedModels = ids)) }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun selectSession(id: String) { currentSessionId = id }
    fun createNewChat() { sessions.add(0, ChatSession(title = "新对话 ${sessions.size + 1}")).also { currentSessionId = sessions[0].id } }
    fun stopGeneration() { currentEventSource?.cancel(); currentGenerationJob?.cancel() }

    fun editMessage(index: Int) {
        val sessionId = currentSessionId ?: return
        val sIdx = sessions.indexOfFirst { it.id == sessionId }
        if (sIdx != -1) {
            inputDraft = sessions[sIdx].messages[index].content
            sessions[sIdx] = sessions[sIdx].copy(messages = sessions[sIdx].messages.take(index))
            stopGeneration()
        }
    }

    fun sendMessage(context: Context, text: String) {
        val sessionId = currentSessionId ?: return
        val sIdx = sessions.indexOfFirst { it.id == sessionId }
        if (sIdx == -1) return

        val imageBase64 = selectedImageUri?.let { uriToContent(context, it) }
        val finalImageUrl = imageBase64?.let { "data:image/jpeg;base64,$it" }

        val userMsg = ChatMessage(text, MessageRole.USER, imageUrl = finalImageUrl)
        sessions[sIdx] = sessions[sIdx].copy(
            messages = sessions[sIdx].messages + userMsg,
            title = if (sessions[sIdx].messages.isEmpty()) text.take(15) else sessions[sIdx].title
        )

        val aiMsgPlaceholder = ChatMessage("", MessageRole.ASSISTANT, steps = emptyList())
        sessions[sIdx] = sessions[sIdx].copy(messages = sessions[sIdx].messages + aiMsgPlaceholder)
        val aiMsgIndex = sessions[sIdx].messages.size - 1

        selectedImageUri = null
        executeMultiStepTurn(sIdx, aiMsgIndex, mutableListOf())
    }

    private fun executeMultiStepTurn(sIdx: Int, aiMsgIndex: Int, historyOfToolCalls: MutableList<Pair<JsonObject, String>>) {
        stopGeneration()
        currentGenerationJob = viewModelScope.launch(Dispatchers.IO) {
            val requestBody = buildRequestBody(sIdx, historyOfToolCalls)
            val request = Request.Builder().url("${settings.baseUrl}/chat/completions")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType())).build()

            var currentContent = ""
            var currentThinking = ""
            val activeToolCalls = mutableMapOf<Int, JsonObject>()

            val listener = object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (data == "[DONE]") {
                        if (activeToolCalls.isNotEmpty()) {
                            val calls = activeToolCalls.values.toList()
                            processAndContinue(sIdx, aiMsgIndex, calls, historyOfToolCalls)
                        } else { finalizeMessage(sIdx, aiMsgIndex) }
                        return
                    }
                    try {
                        val delta = gson.fromJson(data, JsonObject::class.java).getAsJsonArray("choices").get(0).asJsonObject.getAsJsonObject("delta")
                        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull) {
                            currentThinking += delta.get("reasoning_content").asString
                            updateStep(sIdx, aiMsgIndex, StepType.THINKING, currentThinking)
                        }
                        if (delta.has("tool_calls")) {
                            delta.getAsJsonArray("tool_calls").forEach { 
                                val tc = it.asJsonObject
                                val idx = tc.get("index").asInt
                                val obj = activeToolCalls.getOrPut(idx) { JsonObject().apply { 
                                    addProperty("id", tc.get("id")?.asString ?: "")
                                    addProperty("type", "function")
                                    add("function", JsonObject().apply { addProperty("name", ""); addProperty("arguments", "") })
                                } }
                                val func = obj.getAsJsonObject("function")
                                if (tc.has("function")) {
                                    val tcFunc = tc.getAsJsonObject("function")
                                    if (tcFunc.has("name")) func.addProperty("name", func.get("name").asString + tcFunc.get("name").asString)
                                    if (tcFunc.has("arguments")) func.addProperty("arguments", func.get("arguments").asString + tcFunc.get("arguments").asString)
                                }
                                updateStep(sIdx, aiMsgIndex, StepType.TOOL_CALL, func.get("arguments").asString, func.get("name").asString)
                            }
                        }
                        if (delta.has("content") && !delta.get("content").isJsonNull) {
                            if (!delta.has("tool_calls") && activeToolCalls.isEmpty()) {
                                currentContent += delta.get("content").asString
                                updateMessageContent(sIdx, aiMsgIndex, currentContent)
                            }
                        }
                    } catch (e: Exception) {}
                }
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    updateMessageContent(sIdx, aiMsgIndex, "错误: ${t?.message}")
                }
            }
            currentEventSource = EventSources.createFactory(client).newEventSource(request, listener)
        }
    }

    private fun buildRequestBody(sIdx: Int, currentTurnToolHistory: List<Pair<JsonObject, String>>): JsonObject {
        val messages = JsonArray().apply {
            val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
            val systemPrompt = settings.systemPrompt.replace("{CURRENT_TIME}", currentTime).let { base ->
                if (settings.memories.isNotEmpty()) base + "\n\n用户长期记忆：\n" + settings.memories.joinToString("\n") { "- $it" } else base
            }
            add(JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
            
            val history = sessions[sIdx].messages.dropLast(1)
            history.forEach { msg ->
                add(JsonObject().apply {
                    addProperty("role", if(msg.role == MessageRole.USER) "user" else "assistant")
                    if (msg.role == MessageRole.USER && msg.imageUrl != null) {
                        val contentArr = JsonArray().apply {
                            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", msg.content) })
                            add(JsonObject().apply { addProperty("type", "image_url"); add("image_url", JsonObject().apply { addProperty("url", msg.imageUrl) }) })
                        }
                        add("content", contentArr)
                    } else { addProperty("content", msg.content) }
                })
            }

            currentTurnToolHistory.forEach { (call, result) ->
                add(JsonObject().apply {
                    addProperty("role", "assistant")
                    add("tool_calls", JsonArray().apply { add(call) })
                })
                add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", call.get("id").asString)
                    addProperty("content", result)
                })
            }
        }

        return JsonObject().apply {
            addProperty("model", settings.selectedModel)
            add("messages", messages)
            add("tools", getToolsDefinition())
            addProperty("tool_choice", "auto")
            addProperty("stream", true)
        }
    }

    private fun processAndContinue(sIdx: Int, aiMsgIndex: Int, calls: List<JsonObject>, toolHistory: MutableList<Pair<JsonObject, String>>) {
        viewModelScope.launch {
            calls.forEach { call ->
                val func = call.getAsJsonObject("function")
                val name = func.get("name").asString
                val args = func.get("arguments").asString
                val result = withContext(Dispatchers.IO) { executeToolInternal(name, args) }
                toolHistory.add(call to result)
            }
            executeMultiStepTurn(sIdx, aiMsgIndex, toolHistory)
        }
    }

    private fun executeToolInternal(name: String, argsJson: String): String {
        return try {
            val args = gson.fromJson(argsJson, JsonObject::class.java)
            when (name) {
                "save_memory" -> { 
                    val fact = if(args.has("fact")) args.get("fact").asString else "空事实"
                    viewModelScope.launch(Dispatchers.Main) { addMemory(fact) }
                    "已成功保存到记忆。" 
                }
                "get_memories" -> gson.toJson(settings.memories)
                "delete_memory" -> { 
                    val idx = if(args.has("index")) args.get("index").asInt else -1
                    viewModelScope.launch(Dispatchers.Main) { deleteMemory(idx) }
                    "指令已发送。" 
                }
                "update_memory" -> { 
                    val idx = if(args.has("index")) args.get("index").asInt else -1
                    val text = if(args.has("text")) args.get("text").asString else ""
                    viewModelScope.launch(Dispatchers.Main) { updateMemory(idx, text) }
                    "指令已发送。" 
                }
                "exa_search" -> {
                    val query = if(args.has("query")) args.get("query").asString else ""
                    executeExaSearchSync(query)
                }
                else -> "未知工具。"
            }
        } catch (e: Exception) { "工具执行逻辑错: ${e::class.java.simpleName} - ${e.message}" }
    }

    private fun executeExaSearchSync(query: String): String {
        if (settings.exaApiKey.isBlank()) return "错误：未配置 Exa API Key。"
        if (query.isBlank()) return "错误：搜索关键词为空。"
        return try {
            val requestBody = JsonObject().apply {
                addProperty("query", query)
                addProperty("useAutoprompt", true)
                addProperty("numResults", 3)
                add("contents", JsonObject().apply { addProperty("text", true) })
            }
            val request = Request.Builder()
                .url("https://api.exa.ai/search")
                .header("x-api-key", settings.exaApiKey)
                .header("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) "搜索失败：HTTP ${response.code} - ${response.message}"
                else response.body?.string() ?: "搜索结果为空。"
            }
        } catch (e: Exception) {
            "Exa搜索IO异常: ${e::class.java.simpleName} - ${e.message}"
        }
    }

    private fun getToolsDefinition() = JsonArray().apply {
        add(createTool("save_memory", "记录用户信息或重要事实", mapOf("fact" to "string")))
        add(createTool("get_memories", "检索已保存的记忆", emptyMap()))
        add(createTool("exa_search", "在互联网上搜索最新实时信息、新闻、事实核核。", mapOf("query" to "string")))
        add(createTool("delete_memory", "删除记忆条目", mapOf("index" to "integer")))
        add(createTool("update_memory", "修改记忆内容", mapOf("index" to "integer", "text" to "string")))
    }

    private fun createTool(name: String, desc: String, props: Map<String, String>) = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name); addProperty("description", desc)
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                val pObj = JsonObject()
                props.forEach { (k, v) -> pObj.add(k, JsonObject().apply { addProperty("type", v) }) }
                add("properties", pObj)
                if(props.isNotEmpty()) add("required", JsonArray().apply { props.keys.forEach { add(it) } })
            })
        })
    }

    private fun updateStep(sIdx: Int, msgIdx: Int, type: StepType, content: String, toolName: String? = null) {
        if (sIdx >= sessions.size) return
        val msg = sessions[sIdx].messages[msgIdx]
        val steps = msg.steps.toMutableList()
        val existingIdx = steps.indexOfLast { it.type == type && it.toolName == toolName && !it.isFinished }
        if (existingIdx != -1) steps[existingIdx] = steps[existingIdx].copy(content = content)
        else { steps.forEach { it.isFinished = true }; steps.add(ChatStep(type, content, toolName)) }
        viewModelScope.launch(Dispatchers.Main) {
            val updatedMessages = sessions[sIdx].messages.toMutableList()
            updatedMessages[msgIdx] = msg.copy(steps = steps)
            sessions[sIdx] = sessions[sIdx].copy(messages = updatedMessages)
        }
    }

    private fun updateMessageContent(sIdx: Int, msgIdx: Int, content: String) {
        viewModelScope.launch(Dispatchers.Main) {
            if (sIdx < sessions.size && msgIdx < sessions[sIdx].messages.size) {
                val updated = sessions[sIdx].messages.toMutableList()
                updated[msgIdx] = updated[msgIdx].copy(content = content)
                sessions[sIdx] = sessions[sIdx].copy(messages = updated)
            }
        }
    }

    private fun finalizeMessage(sIdx: Int, msgIdx: Int) {
        viewModelScope.launch(Dispatchers.Main) {
            if (sIdx < sessions.size && msgIdx < sessions[sIdx].messages.size) {
                val msg = sessions[sIdx].messages[msgIdx]
                msg.steps.forEach { it.isFinished = true }
                val updated = sessions[sIdx].messages.toMutableList()
                updated[msgIdx] = msg.copy(steps = msg.steps)
                sessions[sIdx] = sessions[sIdx].copy(messages = updated)
            }
        }
    }

    private fun uriToContent(context: Context, uri: Uri): String? = try {
        val bitmap = BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) { null }
}