package mumu.xsy.mumuchat

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.content.ContentValues
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
import kotlinx.coroutines.delay
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
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

import android.util.Log
import mumu.xsy.mumuchat.data.SessionsStore
import mumu.xsy.mumuchat.data.SettingsStore
import mumu.xsy.mumuchat.domain.ExportService
import mumu.xsy.mumuchat.domain.PdfExportService
import mumu.xsy.mumuchat.tools.ToolsCatalog

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_MEMORIES = 80
        private const val MAX_MEMORY_CHARS = 200

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

    private val gson = Gson()
    private val sessionsStore = SessionsStore(application, gson)
    private val settingsStore = SettingsStore(application, gson)
    private val exportService = ExportService()
    private val pdfExportService = PdfExportService()

    var settings by mutableStateOf(settingsStore.load())
        private set

    var sessions = mutableStateListOf<ChatSession>()
    var currentSessionId by mutableStateOf<String?>(null)
    var inputDraft by mutableStateOf("")
    var selectedImageUris = mutableStateListOf<Uri>()

    private var currentGenerationJob: Job? = null
    private var currentEventSource: EventSource? = null
    private var saveSessionsDebounceJob: Job? = null

    var isGenerating by mutableStateOf(false)
        private set

    private fun updateIsGenerating(value: Boolean) {
        viewModelScope.launch(Dispatchers.Main) { isGenerating = value }
    }

    val currentMessages: List<ChatMessage>
        get() = sessions.find { it.id == currentSessionId }?.messages.orEmpty()

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
        viewModelScope.launch {
            loadSessionsFromDataStore()
            if (sessions.isEmpty()) {
                val firstSession = ChatSession(title = "新对话")
                sessions.add(firstSession)
                currentSessionId = firstSession.id
                saveSessionsToDataStore()
            } else if (currentSessionId == null || sessions.none { it.id == currentSessionId }) {
                currentSessionId = sessions.first().id
            }
        }
    }

    /**
     * 从 DataStore 加载会话
     */
    private suspend fun loadSessionsFromDataStore() {
        try {
            val loadedSessions = withContext(Dispatchers.IO) { sessionsStore.load() }
            if (loadedSessions.isEmpty()) return

            withContext(Dispatchers.Main) {
                sessions.clear()
                sessions.addAll(loadedSessions.map { normalizeSession(it) })
                Log.d(TAG, "成功加载 ${loadedSessions.size} 个会话")
                if (currentSessionId == null || sessions.none { it.id == currentSessionId }) {
                    currentSessionId = sessions.firstOrNull()?.id
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载会话失败", e)
        }
    }

    private fun normalizeSession(session: ChatSession): ChatSession {
        val normalizedMessages = session.messages.orEmpty().map { normalizeMessage(it) }
        return session.copy(messages = normalizedMessages)
    }

    private fun normalizeMessage(message: ChatMessage): ChatMessage {
        return message.copy(
            steps = message.steps.orEmpty(),
            toolActions = message.toolActions.orEmpty(),
            imageUrls = message.imageUrls.orEmpty()
        )
    }

    /**
     * 保存会话到 DataStore
     */
    private fun saveSessionsToDataStore() {
        saveSessionsToDataStore(immediate = true)
    }

    private fun saveSessionsToDataStore(immediate: Boolean) {
        if (immediate) {
            saveSessionsDebounceJob?.cancel()
            saveSessionsDebounceJob = null
            viewModelScope.launch(Dispatchers.IO) { persistSessionsSnapshot() }
            return
        }

        if (saveSessionsDebounceJob?.isActive == true) return
        saveSessionsDebounceJob = viewModelScope.launch {
            delay(1200)
            withContext(Dispatchers.IO) { persistSessionsSnapshot() }
        }
    }

    private suspend fun persistSessionsSnapshot() {
        try {
            val snapshot = withContext(Dispatchers.Main) { sessions.toList() }
            sessionsStore.save(snapshot)
            Log.d(TAG, "会话已保存，共 ${snapshot.size} 个")
        } catch (e: Exception) {
            Log.e(TAG, "保存会话失败", e)
        }
    }

    private fun saveSettings(newSettings: AppSettings) {
        settings = newSettings
        settingsStore.save(newSettings)
    }

    fun updateSettings(newSettings: AppSettings) { saveSettings(newSettings) }

    fun addMemory(fact: String) {
        val normalized = fact.trim().replace("\\s+".toRegex(), " ")
        if (normalized.isBlank()) return
        val capped = normalized.take(MAX_MEMORY_CHARS)
        val existing = settings.memories.orEmpty().asSequence()
            .map { it.trim().replace("\\s+".toRegex(), " ") }
            .toSet()
        if (capped in existing) return
        val next = (settings.memories.orEmpty() + capped).takeLast(MAX_MEMORIES)
        saveSettings(settings.copy(memories = next))
    }
    fun deleteMemory(index: Int) {
        val updated = settings.memories.orEmpty().toMutableList().apply { if(index in indices) removeAt(index) }
        saveSettings(settings.copy(memories = updated))
    }
    fun updateMemory(index: Int, text: String) {
        val normalized = text.trim().replace("\\s+".toRegex(), " ")
        if (normalized.isBlank()) return
        val updated = settings.memories.orEmpty().toMutableList().apply { if(index in indices) this[index] = normalized.take(MAX_MEMORY_CHARS) }
        saveSettings(settings.copy(memories = updated))
    }

    fun isVisionModel() = settings.selectedModel.lowercase().run {
        contains("vl") || contains("gemini") || contains("vision") || contains("omni") || contains("glm") || contains("step") || contains("ocr")
    }

    fun createFolder(name: String) {
        val folders = settings.folders.orEmpty()
        if (name.isBlank() || folders.contains(name)) return
        saveSettings(settings.copy(folders = folders + name))
    }

    fun deleteFolder(name: String) {
        val folders = settings.folders.orEmpty()
        saveSettings(settings.copy(folders = folders - name))
        for (i in sessions.indices) {
            val s = sessions[i]
            if (s.folder == name) {
                sessions[i] = s.copy(folder = null)
            }
        }
        saveSessionsToDataStore()
    }

    fun addModel(modelName: String) {
        val models = settings.availableModels.orEmpty()
        if (modelName.isBlank() || models.contains(modelName)) return
        val updatedModels = (models + modelName).sorted()
        saveSettings(settings.copy(availableModels = updatedModels))
    }

    fun addModels(modelNames: List<String>) {
        val updatedModels = (settings.availableModels.orEmpty() + modelNames).distinct().sorted()
        saveSettings(settings.copy(availableModels = updatedModels))
    }

    fun removeModel(modelName: String) {
        val updatedModels = settings.availableModels.orEmpty() - modelName
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
    fun stopGeneration() { currentEventSource?.cancel(); currentGenerationJob?.cancel(); updateIsGenerating(false) }

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
        return exportService.exportSessionToMarkdown(session)
    }

    fun exportCurrentSessionToPlainText(): String {
        val sessionId = currentSessionId ?: return ""
        val session = sessions.find { it.id == sessionId } ?: return ""
        return exportService.exportSessionToPlainText(session)
    }

    fun exportCurrentSessionToHtml(): String {
        val sessionId = currentSessionId ?: return ""
        val session = sessions.find { it.id == sessionId } ?: return ""
        return exportService.exportSessionToHtml(session)
    }

    fun exportCurrentSessionToPdfFile(context: Context): File? {
        val sessionId = currentSessionId ?: return null
        val session = sessions.find { it.id == sessionId } ?: return null
        val text = exportService.exportSessionToPlainText(session)
        return pdfExportService.exportTextToPdfFile(context, session.title, text)
    }

    fun regenerateLastResponse() {
        val sessionId = currentSessionId ?: return
        val sIdx = sessions.indexOfFirst { it.id == sessionId }
        if (sIdx == -1) return

        val messages = sessions[sIdx].messages.orEmpty()
        if (messages.size < 2) return

        val lastIdx = messages.lastIndex
        val last = messages[lastIdx]
        val prev = messages.getOrNull(lastIdx - 1) ?: return
        if (last.role != MessageRole.ASSISTANT || prev.role != MessageRole.USER) return

        stopGeneration()
        val updated = messages.toMutableList()
        updated[lastIdx] = last.copy(content = "", steps = emptyList())
        sessions[sIdx] = sessions[sIdx].copy(messages = updated)
        executeMultiStepTurn(sIdx, lastIdx, mutableListOf())
        saveSessionsToDataStore()
    }

    fun regenerateAssistantAt(messageIndex: Int) {
        val sessionId = currentSessionId ?: return
        val sIdx = sessions.indexOfFirst { it.id == sessionId }
        if (sIdx == -1) return

        val messages = sessions[sIdx].messages.orEmpty()
        val target = messages.getOrNull(messageIndex) ?: return
        if (target.role != MessageRole.ASSISTANT) return

        val userIdx = (messageIndex - 1 downTo 0).firstOrNull { messages[it].role == MessageRole.USER } ?: return

        stopGeneration()
        val truncated = messages.take(userIdx + 1).toMutableList()
        truncated.add(ChatMessage(content = "", role = MessageRole.ASSISTANT, steps = emptyList()))
        sessions[sIdx] = sessions[sIdx].copy(messages = truncated)
        executeMultiStepTurn(sIdx, truncated.lastIndex, mutableListOf())
        saveSessionsToDataStore()
    }

    fun quoteMessage(messageIndex: Int) {
        val sessionId = currentSessionId ?: return
        val sIdx = sessions.indexOfFirst { it.id == sessionId }
        if (sIdx == -1) return

        val msg = sessions[sIdx].messages.orEmpty().getOrNull(messageIndex) ?: return
        val role = when (msg.role) {
            MessageRole.USER -> "用户"
            MessageRole.ASSISTANT -> "AI"
            MessageRole.SYSTEM -> "系统"
            MessageRole.TOOL -> "工具"
        }
        val text = msg.content.trim().take(800).let { if (msg.content.length > 800) it + "\n…" else it }
        val quote = buildString {
            append("> [")
            append(role)
            append("]\n")
            text.lines().forEach { line ->
                append("> ")
                appendLine(line)
            }
            appendLine()
        }
        inputDraft = (inputDraft.trimEnd() + "\n\n" + quote).trimStart()
    }

    fun editMessage(index: Int) {
        val sessionId = currentSessionId ?: return
        val sIdx = sessions.indexOfFirst { it.id == sessionId }
        if (sIdx != -1) {
            val messages = sessions[sIdx].messages.orEmpty()
            inputDraft = messages.getOrNull(index)?.content ?: ""
            sessions[sIdx] = sessions[sIdx].copy(messages = messages.take(index))
            stopGeneration()
        }
    }

    fun sendMessage(context: Context, text: String) {
        val sessionId = currentSessionId ?: return
        val sIdx = sessions.indexOfFirst { it.id == sessionId }
        if (sIdx == -1) return

        viewModelScope.launch(Dispatchers.IO) {
            val selected = withContext(Dispatchers.Main) { selectedImageUris.toList() }
            val imagePaths = selected.mapNotNull { saveImageToInternalStorage(context, it) }
            val finalImageUrls = imagePaths.map { "file://$it" }
            val currentInput = text

            withContext(Dispatchers.Main) {
                val userMsg = ChatMessage(
                    content = currentInput,
                    role = MessageRole.USER,
                    imageUrl = finalImageUrls.firstOrNull(),
                    imageUrls = finalImageUrls
                )
                val aiMsgPlaceholder = ChatMessage(content = "", role = MessageRole.ASSISTANT, steps = emptyList())

                val existing = sessions[sIdx].messages.orEmpty()
                val isFirst = existing.isEmpty()
                val combined = existing + userMsg + aiMsgPlaceholder
                sessions[sIdx] = sessions[sIdx].copy(messages = combined)
                val aiMsgIndex = combined.size - 1

                selectedImageUris.clear()
                executeMultiStepTurn(sIdx, aiMsgIndex, mutableListOf())
                if (isFirst) autoRenameSession(sIdx, currentInput)
                saveSessionsToDataStore()
            }
        }
    }

    fun saveImagesToGallery(context: Context, fileUrls: List<String>): Int {
        var saved = 0
        fileUrls.forEach { url ->
            if (saveSingleImageToGallery(context, url)) saved += 1
        }
        return saved
    }

    private fun saveSingleImageToGallery(context: Context, fileUrl: String): Boolean {
        return try {
            val path = fileUrl.removePrefix("file://")
            val src = File(path)
            if (!src.exists()) return false

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "mumuchat_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MuMuChat")
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out) }
            } ?: return false
            true
        } catch (_: Exception) {
            false
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
            updateIsGenerating(true)
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
                            updateIsGenerating(false)
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
                                        addProperty("id", "")
                                        addProperty("type", "function")
                                        add("function", JsonObject().apply {
                                            addProperty("name", "")
                                            addProperty("arguments", "")
                                        })
                                    }
                                }

                                val tcId = tc.get("id")?.takeIf { !it.isJsonNull }?.asString
                                if (!tcId.isNullOrBlank()) {
                                    obj.addProperty("id", tcId)
                                }
                                val tcType = tc.get("type")?.takeIf { !it.isJsonNull }?.asString
                                if (!tcType.isNullOrBlank()) {
                                    obj.addProperty("type", tcType)
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

                                val args = func.get("arguments")?.asString ?: ""
                                updateStep(
                                    sIdx,
                                    aiMsgIndex,
                                    StepType.TOOL_CALL,
                                    "INPUT\n$args",
                                    func.get("name")?.asString
                                )
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
                    updateIsGenerating(false)
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
                    val urls = msg.imageUrls.orEmpty().ifEmpty { msg.imageUrl?.let { listOf(it) }.orEmpty() }
                    if (msg.role == MessageRole.USER && urls.isNotEmpty()) {
                        val contentArr = JsonArray().apply {
                            add(JsonObject().apply { addProperty("type", "text"); addProperty("text", msg.content) })
                            urls.forEach { u ->
                                val finalUrl = if (u.startsWith("file://")) {
                                    encodeFileToBase64(u.substring(7)) ?: u
                                } else u
                                add(JsonObject().apply { addProperty("type", "image_url"); add("image_url", JsonObject().apply { addProperty("url", finalUrl) }) })
                            }
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
            add("messages", messages); add("tools", ToolsCatalog.getToolsDefinition()); addProperty("tool_choice", "auto"); addProperty("stream", true)
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
                    updateStep(sIdx, aiMsgIndex, StepType.TOOL_CALL, "OUTPUT\n$result", funcName)
                } catch (e: Exception) {
                    Log.e(TAG, "processAndContinue: 工具执行失败", e)
                    val err = "工具执行失败: ${e.message}"
                    toolHistory.add(call to err)
                    val func = call.getAsJsonObject("function")
                    val funcName = func.get("name")?.asString
                    updateStep(sIdx, aiMsgIndex, StepType.TOOL_CALL, "ERROR\n$err", funcName)
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

    private suspend fun executeToolInternal(name: String, argsJson: String, sIdx: Int, aiMsgIndex: Int): String {
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
                        withContext(Dispatchers.Main) { addMemory(fact) }
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
                        withContext(Dispatchers.Main) { deleteMemory(index) }
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
                        withContext(Dispatchers.Main) { updateMemory(index, text) }
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
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            if (scheme != "http" && scheme != "https") return false
            !uri.host.isNullOrBlank()
        } catch (_: Exception) {
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

    private fun updateStep(sIdx: Int, msgIdx: Int, type: StepType, content: String, toolName: String? = null) {
        if (sIdx >= sessions.size) return
        val msg = sessions[sIdx].messages.getOrNull(msgIdx) ?: return
        val steps = msg.steps.toMutableList()
        val existingIdx = steps.indexOfLast { it.type == type && it.toolName == toolName && !it.isFinished }
        val now = System.currentTimeMillis()
        if (existingIdx != -1) {
            val prev = steps[existingIdx]
            if (type == StepType.TOOL_CALL) {
                val trimmed = content.trimStart()
                val isRunningMarker = trimmed.startsWith("执行中:")
                val isInput = trimmed.startsWith("INPUT\n")
                val isOutput = trimmed.startsWith("OUTPUT\n")
                val isError = trimmed.startsWith("ERROR\n")
                val nextInput = if (isInput) trimmed.removePrefix("INPUT\n") else prev.input
                val nextOutput = if (isOutput) trimmed.removePrefix("OUTPUT\n") else prev.output
                val nextError = if (isError) trimmed.removePrefix("ERROR\n") else prev.error

                steps[existingIdx] = prev.copy(
                    content = prev.content,
                    input = nextInput,
                    output = nextOutput,
                    error = nextError,
                    startedAt = prev.startedAt ?: now,
                    finishedAt = if (isOutput || isError) now else prev.finishedAt,
                    isFinished = if (isOutput || isError) true else prev.isFinished
                )
            } else {
                steps[existingIdx] = prev.copy(content = content, startedAt = prev.startedAt ?: now)
            }
        } else {
            steps.forEach { it.isFinished = true }
            if (type == StepType.TOOL_CALL) {
                val trimmed = content.trimStart()
                val isRunningMarker = trimmed.startsWith("执行中:")
                val isInput = trimmed.startsWith("INPUT\n")
                val isOutput = trimmed.startsWith("OUTPUT\n")
                val isError = trimmed.startsWith("ERROR\n")
                steps.add(
                    ChatStep(
                        type = type,
                        content = "",
                        toolName = toolName,
                        isFinished = false,
                        input = if (isInput) trimmed.removePrefix("INPUT\n") else null,
                        output = if (isOutput) trimmed.removePrefix("OUTPUT\n") else null,
                        error = if (isError) trimmed.removePrefix("ERROR\n") else null,
                        startedAt = now,
                        finishedAt = null
                    )
                )
            } else {
                steps.add(ChatStep(type = type, content = content, toolName = toolName, startedAt = now))
            }
        }
        updateMessage(sIdx, msgIdx) { it.copy(steps = steps) }
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
            saveSessionsToDataStore(immediate = false)
        }
    }

    private fun saveImageToInternalStorage(context: Context, uri: Uri): String? {
        var bitmap: Bitmap? = null
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }

            val maxSide = 1280
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxSide || bounds.outHeight / sampleSize > maxSide) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            bitmap = resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            }
            if (bitmap == null) {
                Log.w(TAG, "图片解码失败: $uri")
                return null
            }

            val file = File(context.cacheDir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
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
            val file = File(path)
            if (!file.exists()) return null
            if (file.length() > 3_500_000L) return null
            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (e: Exception) { null }
    }
}
