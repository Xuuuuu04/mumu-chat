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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

// DataStore 扩展
private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "sessions")

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ChatViewModel"
        private val SESSIONS_KEY = stringPreferencesKey("sessions_data")

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

    // 优化的网络客户端配置
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // SSE 需要更长的读取超时
        .writeTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(original.method, original.body)
                .build()
            chain.proceed(request)
        }
        .build()

    init {
        // 加载保存的会话
        viewModelScope.launch(Dispatchers.IO) {
            loadSessionsFromDataStore()
        }
        // 如果没有会话，创建一个新的
        if (sessions.isEmpty()) {
            val firstSession = ChatSession(title = "新对话")
            sessions.add(firstSession)
            currentSessionId = firstSession.id
        }
    }

    /**
     * 从 DataStore 加载会话
     */
    private suspend fun loadSessionsFromDataStore() {
        try {
            val context = getApplication<Application>()
            val json = context.sessionDataStore.data.first()[SESSIONS_KEY]
            if (!json.isNullOrEmpty()) {
                val loadedSessions: List<ChatSession> = gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<ChatSession>>() {}.type)
                sessions.clear()
                sessions.addAll(loadedSessions)
                Log.d(TAG, "成功加载 ${loadedSessions.size} 个会话")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载会话失败", e)
        }
    }

    /**
     * 保存会话到 DataStore
     */
    private fun saveSessionsToDataStore() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val json = gson.toJson(sessions.toList())
                context.sessionDataStore.edit { preferences ->
                    preferences[SESSIONS_KEY] = json
                }
                Log.d(TAG, "会话已保存，共 ${sessions.size} 个")
            } catch (e: Exception) {
                Log.e(TAG, "保存会话失败", e)
            }
        }
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
    fun createNewChat() {
        sessions.add(0, ChatSession(title = "新对话"))
        currentSessionId = sessions[0].id
        saveSessionsToDataStore()
    }
    fun stopGeneration() { currentEventSource?.cancel(); currentGenerationJob?.cancel() }

    fun renameSession(sessionId: String, newTitle: String) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index != -1) {
            sessions[index] = sessions[index].copy(title = newTitle)
            saveSessionsToDataStore()
        }
    }

    fun deleteSession(sessionId: String) {
        sessions.removeAll { it.id == sessionId }
        if (currentSessionId == sessionId) currentSessionId = sessions.firstOrNull()?.id ?: createNewChat().let { sessions[0].id }
        saveSessionsToDataStore()
    }

    fun moveSessionToFolder(sessionId: String, folderName: String?) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index != -1) {
            sessions[index] = sessions[index].copy(folder = folderName)
            saveSessionsToDataStore()
        }
    }

    /**
     * 导出当前会话为 Markdown 格式
     * @return 导出的 Markdown 文本
     */
    fun exportCurrentSessionToMarkdown(): String {
        val sessionId = currentSessionId ?: return ""
        val session = sessions.find { it.id == sessionId } ?: return ""

        val sb = StringBuilder()
        sb.appendLine("# ${session.title}")
        sb.appendLine()
        sb.appendLine("> 导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine()

        session.messages.forEach { msg ->
            val roleName = when (msg.role) {
                MessageRole.USER -> "👤 用户"
                MessageRole.ASSISTANT -> "🤖 AI"
                MessageRole.SYSTEM -> "⚙️ 系统"
                MessageRole.TOOL -> "🔧 工具"
            }
            sb.appendLine("## $roleName")
            sb.appendLine()
            sb.appendLine(msg.content)
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }

        return sb.toString()
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
                val userMsg = ChatMessage(content = currentInput, role = MessageRole.USER, imageUrl = finalImageUrl)
                val aiMsgPlaceholder = ChatMessage(content = "", role = MessageRole.ASSISTANT, steps = emptyList())

                val isFirst = sessions[sIdx].messages.isEmpty()
                sessions[sIdx] = sessions[sIdx].copy(messages = sessions[sIdx].messages + userMsg + aiMsgPlaceholder)
                val aiMsgIndex = sessions[sIdx].messages.size - 1

                selectedImageUri = null
                executeMultiStepTurn(sIdx, aiMsgIndex, mutableListOf())
                if (isFirst) autoRenameSession(sIdx, currentInput)
                saveSessionsToDataStore()
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
            // 验证会话有效性
            if (!isSessionValid(sIdx, aiMsgIndex)) {
                Log.w(TAG, "processAndContinue: 会话无效，跳过工具执行")
                return@launch
            }

            for (call in calls) {
                try {
                    val func = call.getAsJsonObject("function")
                    val funcName = func.get("name")?.asString ?: continue
                    val argsJson = func.get("arguments")?.asString ?: "{}"

                    updateStep(sIdx, aiMsgIndex, StepType.TOOL_CALL, "执行中: $funcName", funcName)

                    val result = withContext(Dispatchers.IO) {
                        executeToolWithRetry(funcName, argsJson, sIdx, aiMsgIndex)
                    }

                    toolHistory.add(call to result)
                    updateStep(sIdx, aiMsgIndex, StepType.TOOL_CALL, result, funcName)
                } catch (e: Exception) {
                    Log.e(TAG, "processAndContinue: 工具执行失败", e)
                    toolHistory.add(call to "工具执行失败: ${e.message}")
                }
            }

            // 继续下一轮
            executeMultiStepTurn(sIdx, aiMsgIndex, toolHistory)
        }
    }

    /**
     * 带重试的工具执行
     */
    private suspend fun executeToolWithRetry(name: String, argsJson: String, sIdx: Int, aiMsgIndex: Int, maxRetries: Int = 2): String {
        repeat(maxRetries + 1) { attempt ->
            try {
                return@executeToolWithRetry executeToolInternal(name, argsJson, sIdx, aiMsgIndex)
            } catch (e: Exception) {
                Log.w(TAG, "工具执行尝试 $attempt 失败: $name", e)
                if (attempt == maxRetries) {
                    return@executeToolWithRetry "工具执行失败 (已重试 $maxRetries 次): ${e.message}"
                }
                delay(500) // 重试前等待
            }
        }
        return "工具执行失败: 未知错误"
    }

    /**
     * 验证会话有效性
     */
    private fun isSessionValid(sIdx: Int, aiMsgIndex: Int): Boolean {
        if (sIdx >= sessions.size) {
            Log.w(TAG, "会话索引无效: sIdx=$sIdx, sessions.size=${sessions.size}")
            return false
        }
        if (aiMsgIndex >= sessions[sIdx].messages.size) {
            Log.w(TAG, "消息索引无效: aiMsgIndex=$aiMsgIndex, messages.size=${sessions[sIdx].messages.size}")
            return false
        }
        return true
    }

    private fun executeToolInternal(name: String, argsJson: String, sIdx: Int, aiMsgIndex: Int): String {
        // 验证参数
        if (argsJson.isBlank()) {
            Log.w(TAG, "工具参数为空: $name")
            return "错误: 工具参数为空"
        }

        return try {
            val args = try {
                gson.fromJson(argsJson, JsonObject::class.java) ?: JsonObject()
            } catch (e: Exception) {
                Log.w(TAG, "解析工具参数失败: $argsJson", e)
                JsonObject()
            }

            when (name) {
                "save_memory" -> {
                    val fact = args.get("fact")?.asString ?: ""
                    if (fact.isBlank()) {
                        "错误: memory 内容不能为空"
                    } else {
                        viewModelScope.launch(Dispatchers.Main) { addMemory(fact) }
                        "已保存记忆: ${fact.take(50)}..."
                    }
                }
                "get_memories" -> {
                    val memories = settings.memories
                    if (memories.isEmpty()) {
                        "暂无记忆"
                    } else {
                        gson.toJson(memories)
                    }
                }
                "delete_memory" -> {
                    val index = args.get("index")?.asInt
                    if (index == null || index < 0 || index >= settings.memories.size) {
                        "错误: 无效的记忆索引 $index (共 ${settings.memories.size} 条)"
                    } else {
                        viewModelScope.launch(Dispatchers.Main) { deleteMemory(index) }
                        "已删除第 ${index + 1} 条记忆"
                    }
                }
                "update_memory" -> {
                    val index = args.get("index")?.asInt
                    val text = args.get("text")?.asString ?: ""
                    if (index == null || index < 0 || index >= settings.memories.size) {
                        "错误: 无效的记忆索引 $index"
                    } else if (text.isBlank()) {
                        "错误: 新内容不能为空"
                    } else {
                        viewModelScope.launch(Dispatchers.Main) { updateMemory(index, text) }
                        "已更新记忆"
                    }
                }
                "exa_search" -> {
                    val query = args.get("query")?.asString ?: ""
                    if (query.isBlank()) {
                        "错误: 搜索关键词不能为空"
                    } else if (settings.exaApiKey.isBlank()) {
                        "错误: 未配置 Exa Search Key，请在设置中配置"
                    } else {
                        executeExaSearchSync(query)
                    }
                }
                "browse_url" -> {
                    val url = args.get("url")?.asString ?: ""
                    if (url.isBlank()) {
                        "错误: URL 不能为空"
                    } else if (!isValidUrl(url)) {
                        "错误: 无效的 URL 格式"
                    } else {
                        executeBrowseUrlSync(url)
                    }
                }
                "calculate" -> {
                    val code = args.get("code")?.asString ?: ""
                    if (code.isBlank()) {
                        "错误: 计算代码不能为空"
                    } else {
                        executeJsCalculate(code)
                    }
                }
                "text_to_image" -> {
                    val prompt = args.get("prompt")?.asString ?: ""
                    if (prompt.isBlank()) {
                        "错误: 图片描述不能为空"
                    } else if (settings.apiKey.isBlank()) {
                        "错误: 未配置 API Key"
                    } else {
                        executeTextToImageSync(prompt, sIdx, aiMsgIndex)
                    }
                }
                "get_news_board" -> {
                    val board = args.get("board")?.asString ?: ""
                    if (board.isBlank()) {
                        "错误: 热搜板块不能为空"
                    } else {
                        executeGetNewsBoardSync(board)
                    }
                }
                else -> "未知工具: $name"
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeToolInternal 执行异常: $name", e)
            "工具执行错误: ${e.message}"
        }
    }

    /**
     * 验证 URL 格式
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            val pattern = "^(https?://)?([\\w\\-]+\\.)+[\\w\\-]+(/[\\w\\-./?%&=]*)?$".toRegex()
            pattern.matches(url)
        } catch (e: Exception) {
            false
        }
    }

    private fun executeGetNewsBoardSync(board: String): String = try {
        val request = Request.Builder()
            .url("https://60s.viki.moe/v2/$board")
            .header("User-Agent", "MuMuChat/2.1")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "获取热搜失败: HTTP ${response.code}")
                return@use "获取热搜失败: HTTP ${response.code}"
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Log.w(TAG, "获取热搜失败: 空响应")
                return@use "获取热搜失败: 空响应"
            }

            Log.d(TAG, "热搜获取成功: ${body.length} 字符")
            body
        }
    } catch (e: java.net.SocketTimeoutException) {
        Log.e(TAG, "获取热搜超时", e)
        "获取热搜超时，请重试"
    } catch (e: Exception) {
        Log.e(TAG, "获取热搜异常", e)
        "获取热搜失败: ${e.message}"
    }

    private fun executeTextToImageSync(prompt: String, sIdx: Int, aiMsgIndex: Int): String = try {
        val requestBody = JsonObject().apply {
            addProperty("model", "black-forest-labs/FLUX.1-schnell")
            addProperty("prompt", prompt)
            addProperty("image_size", "1024x1024")
            addProperty("num_inference_steps", 4)
            addProperty("width", 1024)
            addProperty("height", 1024)
        }

        val request = Request.Builder()
            .url("${settings.baseUrl}/images/generations")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "生图失败: HTTP ${response.code}")
                return@use "绘图失败: HTTP ${response.code}"
            }

            val bodyString = response.body?.string()
            if (bodyString.isNullOrBlank()) {
                Log.w(TAG, "生图失败: 空响应")
                return@use "绘图失败: 空响应"
            }

            val jsonObject = try {
                gson.fromJson(bodyString, JsonObject::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "生图响应解析失败", e)
                return@use "绘图失败: 响应解析错误"
            }

            val images = jsonObject.getAsJsonArray("images")
            if (images == null || images.size() == 0) {
                Log.w(TAG, "生图失败: 无 images 字段")
                return@use "绘图失败: 无 images 字段"
            }

            val imageObj = images.get(0)?.asJsonObject
            val imageUrl = imageObj?.get("url")?.asString ?: imageObj?.get("b64_json")?.asString
            if (imageUrl == null) {
                Log.w(TAG, "生图失败: 无 url/b64_json 字段")
                return@use "绘图失败: 无图片数据"
            }

            viewModelScope.launch(Dispatchers.Main) {
                try {
                    if (isSessionValid(sIdx, aiMsgIndex)) {
                        val updated = sessions[sIdx].messages.toMutableList()
                        updated[aiMsgIndex] = updated[aiMsgIndex].copy(
                            imageUrl = if (imageUrl.startsWith("data:")) {
                                "data:image/png;base64,${imageUrl.substringAfter("base64,")}"
                            } else imageUrl
                        )
                        sessions[sIdx] = sessions[sIdx].copy(messages = updated)
                        Log.d(TAG, "图片已生成并更新到消息")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "更新图片 URL 失败", e)
                }
            }
            "图片已生成"
        }
    } catch (e: java.net.SocketTimeoutException) {
        Log.e(TAG, "生图超时", e)
        "生图超时，请重试"
    } catch (e: Exception) {
        Log.e(TAG, "生图异常", e)
        "绘图失败: ${e.message}"
    }

    private fun executeBrowseUrlSync(url: String): String = try {
        val doc = Jsoup.connect(url)
            .timeout(15000) // 15秒超时
            .userAgent("Mozilla/5.0 (Android; MuMuChat/2.1)")
            .followRedirects(true)
            .maxBodySize(5 * 1024 * 1024) // 5MB 限制
            .get()

        val title = doc.title().takeIf { it.isNotBlank() } ?: "无标题"
        val text = (doc.select("article").first() ?: doc.body() ?: doc).text()
        val truncated = if (text.length > 10000) {
            text.take(10000) + "\n\n[内容已截断]"
        } else text

        Log.d(TAG, "网页获取成功: $title, ${truncated.length} 字符")
        "网页标题: $title\n\n$truncated"
    } catch (e: java.net.SocketTimeoutException) {
        Log.e(TAG, "网页加载超时: $url", e)
        "网页加载超时，请检查网络或尝试其他 URL"
    } catch (e: org.jsoup.HttpStatusException) {
        Log.e(TAG, "网页请求失败: ${e.statusCode}", e)
        "网页请求失败 (HTTP ${e.statusCode}): ${e.message}"
    } catch (e: Exception) {
        Log.e(TAG, "网页浏览异常: $url", e)
        "浏览失败: ${e.message}"
    }

    private fun executeJsCalculate(code: String): String {
        var rhinoContext: RhinoContext? = null
        return try {
            rhinoContext = RhinoContext.enter().apply {
                optimizationLevel = -1
            }
            val scope = rhinoContext.initStandardObjects()

            // 限制执行时间和复杂度
            val limitedCode = """
                (function() {
                    try {
                        $code
                    } catch (e) {
                        return '错误: ' + e.message;
                    }
                })();
            """.trimIndent()

            val result = rhinoContext.evaluateString(scope, limitedCode, "JS", 1, null)
            val output = RhinoContext.toString(result)

            if (output.isNullOrBlank()) {
                "计算完成 (无输出)"
            } else {
                output
            }
        } catch (e: Exception) {
            Log.e(TAG, "JS 计算异常", e)
            when {
                e.message?.contains("ReferenceError") == true -> "ReferenceError: 变量未定义"
                e.message?.contains("SyntaxError") == true -> "SyntaxError: 语法错误"
                e.message?.contains("TypeError") == true -> "TypeError: 类型错误"
                else -> "计算错误: ${e.message}"
            }
        } finally {
            try {
                RhinoContext.exit()
            } catch (e: Exception) {
                Log.w(TAG, "Rhino 上下文退出异常", e)
            }
        }
    }

    private fun executeExaSearchSync(query: String): String = try {
        val requestBody = JsonObject().apply {
            addProperty("query", query)
            addProperty("useAutoprompt", true)
            addProperty("numResults", 5)
            addProperty("timeout", 10) // 10秒超时
            add("contents", JsonObject().apply {
                addProperty("text", true)
                addProperty("summary", true)
            })
        }

        val request = Request.Builder()
            .url("https://api.exa.ai/search")
            .header("x-api-key", settings.exaApiKey)
            .header("Content-Type", "application/json")
            .header("User-Agent", "MuMuChat/2.1")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Exa 搜索失败: HTTP ${response.code}")
                return@use when (response.code) {
                    401 -> "搜索失败: API Key 无效"
                    429 -> "搜索失败: 请求过于频繁，请稍后重试"
                    else -> "搜索失败: HTTP ${response.code}"
                }
            }

            val bodyString = response.body?.string()
            if (bodyString.isNullOrBlank()) {
                Log.w(TAG, "Exa 搜索空响应")
                return@use "搜索失败: 空响应"
            }

            val jsonObject = try {
                gson.fromJson(bodyString, JsonObject::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Exa 响应解析失败", e)
                return@use "搜索失败: 响应解析错误"
            }

            val results = jsonObject.getAsJsonArray("results")
            val resultsCount = results?.size() ?: 0
            if (resultsCount == 0) {
                Log.w(TAG, "Exa 搜索无结果")
                return@use "未找到相关结果"
            }

            val sb = StringBuilder()
            sb.appendLine("搜索结果 ($resultsCount 条):")
            sb.appendLine()

            results.forEachIndexed { index, result ->
                val obj = result.asJsonObject
                val title = obj.get("title")?.asString ?: "无标题"
                val url = obj.get("url")?.asString ?: ""
                val snippet = obj.get("description")?.asString ?: obj.get("text")?.asString ?: ""
                val snippetClean = snippet.take(200).replace("\n", " ")

                sb.appendLine("${index + 1}. $title")
                sb.appendLine("   $snippetClean")
                sb.appendLine("   来源: $url")
                sb.appendLine()
            }

            Log.d(TAG, "Exa 搜索成功: $resultsCount 条结果")
            sb.toString()
        }
    } catch (e: java.net.SocketTimeoutException) {
        Log.e(TAG, "Exa 搜索超时", e)
        "搜索超时，请重试"
    } catch (e: Exception) {
        Log.e(TAG, "Exa 搜索异常", e)
        "搜索失败: ${e.message}"
    }

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
            saveSessionsToDataStore()
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
