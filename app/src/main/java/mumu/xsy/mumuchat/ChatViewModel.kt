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
import org.jsoup.Jsoup
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

import android.util.Log

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ChatViewModel"

        private const val CORE_SYSTEM_PROMPT = """
## 核心任务流 (ReAct 规范)
当你收到用户指令后，必须遵循以下内部逻辑：
1. **拆解 (Decompose)**: 将复杂问题拆分为多个子问题。
2. **推理 (Thought)**: 明确当前已知什么，还需要搜索什么。
3. **行动 (Action)**: 调用 `exa_search` 进行搜索，或 `get_memories` 检索背景。
4. **观察 (Observation)**: 分析搜索到的结果是否真实、是否有冲突。
5. **迭代 (Iterate)**: 如果结果不充分，继续调整关键词进行二轮搜索。
6. **总结 (Final Answer)**: 整合所有信息，给出详尽、诚实、无幻觉的回答。

## 搜索与工具使用准则
- **时效性优先**: 涉及新闻、数据、价格等，必须联网。
- **事实核查**: 对不确定的事实进行交叉验证。
- **工具静默**: 严禁在输出 `tool_calls` 的同时输出任何自然语言。
- **记忆更新**: 如果发现用户的偏好发生了变化，主动调用 `update_memory`。

## 回答风格
- 使用 Markdown 格式，层级分明。
- 引用搜索来源（如果有）。"""
    }

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

    fun createFolder(name: String) {
        if (name.isBlank() || settings.folders.contains(name)) return
        saveSettings(settings.copy(folders = settings.folders + name))
    }

    fun deleteFolder(name: String) {
        saveSettings(settings.copy(folders = settings.folders - name))
        sessions.forEach { if (it.folder == name) it.copy(folder = null) }
    }

    fun addModel(modelName: String) {
        if (modelName.isBlank() || settings.availableModels.contains(modelName)) return
        val updatedModels = (settings.availableModels + modelName).sorted()
        saveSettings(settings.copy(availableModels = updatedModels))
    }

    fun addModels(modelNames: List<String>) {
        val updatedModels = (settings.availableModels + modelNames).distinct().sorted()
        saveSettings(settings.copy(availableModels = updatedModels))
    }

    fun removeModel(modelName: String) {
        val updatedModels = settings.availableModels - modelName
        saveSettings(settings.copy(availableModels = updatedModels))
        if (settings.selectedModel == modelName) {
            saveSettings(settings.copy(selectedModel = updatedModels.firstOrNull() ?: ""))
        }
    }

    fun fetchAvailableModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${settings.baseUrl}/models")
                    .header("Authorization", "Bearer ${settings.apiKey}")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "获取模型列表失败: HTTP ${response.code}")
                        return@use
                    }

                    val bodyString = response.body?.string()
                    if (bodyString.isNullOrEmpty()) {
                        Log.w(TAG, "获取模型列表失败: 响应体为空")
                        return@use
                    }

                    val jsonObject = gson.fromJson(bodyString, JsonObject::class.java)
                    val dataArray = jsonObject.getAsJsonArray("data") ?: run {
                        Log.w(TAG, "获取模型列表失败: 无 data 字段")
                        return@use
                    }

                    val ids = dataArray.mapNotNull { it.asJsonObject.get("id")?.asString }
                    launch(Dispatchers.Main) {
                        saveSettings(settings.copy(fetchedModels = ids.sorted()))
                    }
                    Log.d(TAG, "成功获取 ${ids.size} 个模型")
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取模型列表异常", e)
            }
        }
    }

    fun selectSession(id: String) { currentSessionId = id }
    fun createNewChat() { sessions.add(0, ChatSession(title = "新对话")).also { currentSessionId = sessions[0].id } }
    fun stopGeneration() { currentEventSource?.cancel(); currentGenerationJob?.cancel() }

    fun renameSession(sessionId: String, newTitle: String) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index != -1) sessions[index] = sessions[index].copy(title = newTitle)
    }

    fun deleteSession(sessionId: String) {
        sessions.removeAll { it.id == sessionId }
        if (currentSessionId == sessionId) currentSessionId = sessions.firstOrNull()?.id ?: createNewChat().let { sessions[0].id }
    }

    fun moveSessionToFolder(sessionId: String, folderName: String?) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index != -1) sessions[index] = sessions[index].copy(folder = folderName)
    }

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

        viewModelScope.launch(Dispatchers.IO) {
            val imagePath = selectedImageUri?.let { saveImageToInternalStorage(context, it) }
            val finalImageUrl = imagePath?.let { "file://$it" }
            val currentInput = text

            withContext(Dispatchers.Main) {
                val userMsg = ChatMessage(currentInput, MessageRole.USER, imageUrl = finalImageUrl)
                val aiMsgPlaceholder = ChatMessage("", MessageRole.ASSISTANT, steps = emptyList())
                
                val isFirst = sessions[sIdx].messages.isEmpty()
                sessions[sIdx] = sessions[sIdx].copy(messages = sessions[sIdx].messages + userMsg + aiMsgPlaceholder)
                val aiMsgIndex = sessions[sIdx].messages.size - 1
                
                selectedImageUri = null
                executeMultiStepTurn(sIdx, aiMsgIndex, mutableListOf())
                if (isFirst) autoRenameSession(sIdx, currentInput)
            }
        }
    }

    private fun autoRenameSession(sIdx: Int, userFirstMsg: String) {
        if (sIdx >= sessions.size) {
            Log.w(TAG, "autoRenameSession: 无效的会话索引 $sIdx")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prompt = "总结一个2-5个字的对话标题，不要标点符号。用户说: \"$userFirstMsg\""
                val requestBody = JsonObject().apply {
                    addProperty("model", "deepseek-ai/DeepSeek-V3")
                    add("messages", JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt) }) })
                }
                val request = Request.Builder()
                    .url("${settings.baseUrl}/chat/completions")
                    .header("Authorization", "Bearer ${settings.apiKey}")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "自动重命名失败: HTTP ${response.code}")
                        return@use
                    }

                    val bodyString = response.body?.string()
                    if (bodyString.isNullOrEmpty()) {
                        Log.w(TAG, "自动重命名失败: 响应体为空")
                        return@use
                    }

                    val jsonObject = gson.fromJson(bodyString, JsonObject::class.java)
                    val choices = jsonObject.getAsJsonArray("choices")
                    if (choices == null || choices.size() == 0) {
                        Log.w(TAG, "自动重命名失败: 无 choices 字段")
                        return@use
                    }

                    val title = choices.get(0).asJsonObject
                        .getAsJsonObject("message")
                        ?.get("content")
                        ?.asString
                        ?.trim()
                        ?.replace("\"", "")
                        ?: run {
                            Log.w(TAG, "自动重命名失败: 无法解析标题")
                            return@use
                        }

                    launch(Dispatchers.Main) {
                        renameSession(sessions[sIdx].id, title)
                        Log.d(TAG, "会话已自动重命名: $title")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "自动重命名异常", e)
            }
        }
    }

    private fun executeMultiStepTurn(sIdx: Int, aiMsgIndex: Int, historyOfToolCalls: MutableList<Pair<JsonObject, String>>) {
        stopGeneration()
        currentGenerationJob = viewModelScope.launch(Dispatchers.IO) {
            val requestBody = buildRequestBody(sIdx, historyOfToolCalls)
            val request = Request.Builder()
                .url("${settings.baseUrl}/chat/completions")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            var currentContent = ""
            var currentThinking = ""
            val activeToolCalls = mutableMapOf<Int, JsonObject>()

            val listener = object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (data == "[DONE]") {
                        if (activeToolCalls.isNotEmpty()) {
                            processAndContinue(sIdx, aiMsgIndex, activeToolCalls.values.toList(), historyOfToolCalls)
                        } else {
                            finalizeMessage(sIdx, aiMsgIndex)
                        }
                        return
                    }

                    try {
                        val jsonObject = gson.fromJson(data, JsonObject::class.java)
                        val choices = jsonObject.getAsJsonArray("choices")
                        if (choices == null || choices.size() == 0) {
                            Log.w(TAG, "SSE 事件无 choices 数据")
                            return
                        }

                        val delta = choices.get(0).asJsonObject.getAsJsonObject("delta")
                        if (delta == null) {
                            Log.w(TAG, "SSE 事件无 delta 数据")
                            return
                        }

                        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull) {
                            currentThinking += delta.get("reasoning_content").asString
                            updateStep(sIdx, aiMsgIndex, StepType.THINKING, currentThinking)
                        }

                        if (delta.has("tool_calls")) {
                            delta.getAsJsonArray("tool_calls").forEach { tcElement ->
                                val tc = tcElement.asJsonObject
                                val idx = tc.get("index")?.asInt ?: return@forEach

                                val obj = activeToolCalls.getOrPut(idx) {
                                    JsonObject().apply {
                                        addProperty("id", tc.get("id")?.asString ?: "")
                                        addProperty("type", "function")
                                        add("function", JsonObject().apply {
                                            addProperty("name", "")
                                            addProperty("arguments", "")
                                        })
                                    }
                                }

                                val func = obj.getAsJsonObject("function")
                                if (tc.has("function")) {
                                    val tcFunc = tc.getAsJsonObject("function")
                                    val currentName = func.get("name")?.asString ?: ""
                                    val currentArgs = func.get("arguments")?.asString ?: ""

                                    if (tcFunc.has("name")) {
                                        func.addProperty("name", currentName + tcFunc.get("name").asString)
                                    }
                                    if (tcFunc.has("arguments")) {
                                        func.addProperty("arguments", currentArgs + tcFunc.get("arguments").asString)
                                    }
                                }

                                updateStep(sIdx, aiMsgIndex, StepType.TOOL_CALL,
                                    func.get("arguments")?.asString ?: "",
                                    func.get("name")?.asString)
                            }
                        }

                        if (delta.has("content") && !delta.get("content").isJsonNull) {
                            if (!delta.has("tool_calls") && activeToolCalls.isEmpty()) {
                                currentContent += delta.get("content").asString
                                updateMessageContent(sIdx, aiMsgIndex, currentContent)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "SSE 事件解析异常", e)
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val errorMsg = t?.message ?: response?.message ?: "未知错误"
                    Log.e(TAG, "SSE 连接失败: $errorMsg")
                    updateMessageContent(sIdx, aiMsgIndex, "错误: $errorMsg")
                }
            }

            currentEventSource = EventSources.createFactory(client).newEventSource(request, listener)
        }
    }

    private fun buildRequestBody(sIdx: Int, currentTurnToolHistory: List<Pair<JsonObject, String>>): JsonObject {
        val messages = JsonArray().apply {
            val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
            val systemPrompt = """$CORE_SYSTEM_PROMPT
当前系统时间: $currentTime

用户个性化设定:
${settings.userPersona}
            """.trimIndent().let { base ->
                if (settings.memories.isNotEmpty()) base + "\n\n用户记忆：\n" + settings.memories.joinToString("\n") { "- $it" } else base
            }
            add(JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
            
            val history = sessions[sIdx].messages.dropLast(1)
            history.forEach { msg ->
                add(JsonObject().apply {
                    addProperty("role", if(msg.role == MessageRole.USER) "user" else "assistant")
                    if (msg.role == MessageRole.USER && msg.imageUrl != null) {
                        val finalUrl = if (msg.imageUrl.startsWith("file://")) {
                            encodeFileToBase64(msg.imageUrl.substring(7)) ?: msg.imageUrl
                        } else msg.imageUrl

                        val contentArr = JsonArray().apply {
                            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", msg.content) })
                            add(JsonObject().apply { addProperty("type", "image_url"); add("image_url", JsonObject().apply { addProperty("url", finalUrl) }) })
                        }
                        add("content", contentArr)
                    } else { addProperty("content", msg.content) }
                })
            }

            currentTurnToolHistory.forEach { (call, result) ->
                add(JsonObject().apply { addProperty("role", "assistant"); add("tool_calls", JsonArray().apply { add(call) }) })
                add(JsonObject().apply { addProperty("role", "tool"); addProperty("tool_call_id", call.get("id").asString); addProperty("content", result) })
            }
        }

        return JsonObject().apply {
            addProperty("model", settings.selectedModel)
            add("messages", messages); add("tools", getToolsDefinition()); addProperty("tool_choice", "auto"); addProperty("stream", true)
        }
    }

    private fun processAndContinue(sIdx: Int, aiMsgIndex: Int, calls: List<JsonObject>, toolHistory: MutableList<Pair<JsonObject, String>>) {
        viewModelScope.launch {
            calls.forEach { call ->
                val func = call.getAsJsonObject("function")
                val result = withContext(Dispatchers.IO) { executeToolInternal(func.get("name").asString, func.get("arguments").asString, sIdx, aiMsgIndex) }
                toolHistory.add(call to result)
            }
            executeMultiStepTurn(sIdx, aiMsgIndex, toolHistory)
        }
    }

    private fun executeToolInternal(name: String, argsJson: String, sIdx: Int, aiMsgIndex: Int): String {
        return try {
            val args = gson.fromJson(argsJson, JsonObject::class.java)
            when (name) {
                "save_memory" -> { viewModelScope.launch(Dispatchers.Main) { addMemory(args.get("fact").asString) }; "已保存。" }
                "get_memories" -> gson.toJson(settings.memories)
                "delete_memory" -> { viewModelScope.launch(Dispatchers.Main) { deleteMemory(args.get("index").asInt) }; "指令已发。" }
                "update_memory" -> { viewModelScope.launch(Dispatchers.Main) { updateMemory(args.get("index").asInt, args.get("text").asString) }; "指令已发。" }
                "exa_search" -> executeExaSearchSync(args.get("query").asString)
                "browse_url" -> executeBrowseUrlSync(args.get("url").asString)
                "calculate" -> executeJsCalculate(args.get("code").asString)
                "text_to_image" -> executeTextToImageSync(args.get("prompt").asString, sIdx, aiMsgIndex)
                "get_news_board" -> executeGetNewsBoardSync(args.get("board").asString)
                else -> "未知工具。"
            }
        } catch (e: Exception) { "错误: ${e.message}" }
    }

    private fun executeGetNewsBoardSync(board: String): String = try {
        client.newCall(Request.Builder().url("https://60s.viki.moe/v2/$board").build()).execute().use { it.body?.string() ?: "空" }
    } catch (e: Exception) { "失败: ${e.message}" }

    private fun executeTextToImageSync(prompt: String, sIdx: Int, aiMsgIndex: Int): String = try {
        val requestBody = JsonObject().apply {
            addProperty("model", "black-forest-labs/FLUX.1-schnell")
            addProperty("prompt", prompt)
            addProperty("image_size", "1024x1024")
            addProperty("num_inference_steps", 4)
        }

        val request = Request.Builder()
            .url("${settings.baseUrl}/images/generations")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "生图失败: HTTP ${response.code}")
                return@use "绘图失败: HTTP ${response.code}"
            }

            val bodyString = response.body?.string()
            if (bodyString.isNullOrEmpty()) {
                Log.w(TAG, "生图失败: 响应体为空")
                return@use "绘图失败: 响应体为空"
            }

            val jsonObject = gson.fromJson(bodyString, JsonObject::class.java)
            val images = jsonObject.getAsJsonArray("images")
            if (images == null || images.size() == 0) {
                Log.w(TAG, "生图失败: 无 images 字段")
                return@use "绘图失败: 无 images 字段"
            }

            val imageUrl = images.get(0).asJsonObject.get("url")?.asString
            if (imageUrl == null) {
                Log.w(TAG, "生图失败: 无 url 字段")
                return@use "绘图失败: 无 url 字段"
            }

            viewModelScope.launch(Dispatchers.Main) {
                try {
                    if (sIdx < sessions.size && aiMsgIndex < sessions[sIdx].messages.size) {
                        val updated = sessions[sIdx].messages.toMutableList()
                        updated[aiMsgIndex] = updated[aiMsgIndex].copy(imageUrl = imageUrl)
                        sessions[sIdx] = sessions[sIdx].copy(messages = updated)
                        Log.d(TAG, "图片已生成并更新到消息")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "更新图片 URL 失败", e)
                }
            }
            "生成的图片已展示。"
        }
    } catch (e: Exception) {
        Log.e(TAG, "生图异常", e)
        "绘图失败: ${e.message}"
    }

    private fun executeBrowseUrlSync(url: String): String = try {
        val doc = Jsoup.connect(url).timeout(10000).get()
        val text = (doc.select("article").first() ?: doc.body()).text()
        if (text.length > 8000) text.take(8000) + "..." else text
    } catch (e: Exception) { "浏览失败: ${e.message}" }

    private fun executeJsCalculate(code: String): String {
        var rhinoContext: RhinoContext? = null
        return try {
            rhinoContext = RhinoContext.enter().apply { optimizationLevel = -1 }
            val scope = rhinoContext.initStandardObjects()
            val result = rhinoContext.evaluateString(scope, code, "JS", 1, null)
            RhinoContext.toString(result)
        } catch (e: Exception) {
            Log.e(TAG, "JS 计算异常: $code", e)
            "计算错误: ${e.message}"
        } finally {
            try {
                RhinoContext.exit()
            } catch (e: Exception) {
                Log.w(TAG, "Rhino 上下文退出异常", e)
            }
        }
    }

    private fun executeExaSearchSync(query: String): String = try {
        val requestBody = JsonObject().apply { addProperty("query", query); addProperty("useAutoprompt", true); addProperty("numResults", 3); add("contents", JsonObject().apply { addProperty("text", true) }) }
        client.newCall(Request.Builder().url("https://api.exa.ai/search").header("x-api-key", settings.exaApiKey).post(requestBody.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.body?.string() ?: "空" }
    } catch (e: Exception) { "搜索异常: ${e.message}" }

    private fun getToolsDefinition() = JsonArray().apply {
        add(createTool("save_memory", "记录用户信息", mapOf("fact" to "string")))
        add(createTool("get_memories", "检索记忆", emptyMap()))
        add(createTool("exa_search", "联网搜索", mapOf("query" to "string")))
        add(createTool("browse_url", "阅读网页全文", mapOf("url" to "string")))
        add(createTool("calculate", "执行JS计算", mapOf("code" to "string")))
        add(createTool("text_to_image", "根据描述创作图片", mapOf("prompt" to "string")))
        add(createTool("get_news_board", "获取新闻热搜(60s, weibo, zhihu, bili, douyin)", mapOf("board" to "string")))
        add(createTool("delete_memory", "删除记忆", mapOf("index" to "integer")))
        add(createTool("update_memory", "更新记忆", mapOf("index" to "integer", "text" to "string")))
    }

    private fun createTool(name: String, desc: String, props: Map<String, String>) = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name); addProperty("description", desc)
            add("parameters", JsonObject().apply {
                addProperty("type", "object"); val pObj = JsonObject()
                props.forEach { (k, v) -> pObj.add(k, JsonObject().apply { addProperty("type", v) }) }
                add("properties", pObj); if(props.isNotEmpty()) add("required", JsonArray().apply { props.keys.forEach { add(it) } })
            })
        })
    }

    private fun updateStep(sIdx: Int, msgIdx: Int, type: StepType, content: String, toolName: String? = null) {
        if (sIdx >= sessions.size) return
        val msg = sessions[sIdx].messages.getOrNull(msgIdx) ?: return
        val steps = msg.steps.toMutableList()
        val existingIdx = steps.indexOfLast { it.type == type && it.toolName == toolName && !it.isFinished }
        if (existingIdx != -1) {
            steps[existingIdx] = steps[existingIdx].copy(content = content)
        } else {
            steps.forEach { it.isFinished = true }
            steps.add(ChatStep(type, content, toolName))
        }
        updateMessage(sIdx) { it.copy(steps = steps) }
    }

    private fun updateMessageContent(sIdx: Int, msgIdx: Int, content: String) {
        updateMessage(sIdx, msgIdx) { it.copy(content = content) }
    }

    private fun finalizeMessage(sIdx: Int, msgIdx: Int) {
        updateMessage(sIdx, msgIdx) { msg ->
            msg.steps.forEach { it.isFinished = true }
            msg.copy(steps = msg.steps)
        }
    }

    /**
     * 通用消息更新方法
     * 消除重复的消息更新模式
     */
    private fun updateMessage(
        sIdx: Int,
        msgIdx: Int? = null,
        transform: (ChatMessage) -> ChatMessage
    ) {
        viewModelScope.launch(Dispatchers.Main) {
            if (sIdx >= sessions.size) return@launch

            val session = sessions[sIdx]
            val messages = if (msgIdx != null) {
                val msg = session.messages.getOrNull(msgIdx) ?: return@launch
                session.messages.toMutableList().apply {
                    this[msgIdx] = transform(msg)
                }
            } else {
                session.messages.map { transform(it) }
            }
            sessions[sIdx] = session.copy(messages = messages)
        }
    }

    private fun saveImageToInternalStorage(context: Context, uri: Uri): String? {
        var bitmap: Bitmap? = null
        return try {
            bitmap = BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
            if (bitmap == null) {
                Log.w(TAG, "图片解码失败: $uri")
                return null
            }

            val file = File(context.cacheDir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            Log.d(TAG, "图片已保存: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存图片失败: ${e.message}", e)
            null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun encodeFileToBase64(path: String): String? {
        return try {
             val bytes = File(path).readBytes()
             val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
             "data:image/jpeg;base64,$base64"
        } catch (e: Exception) { null }
    }
}
