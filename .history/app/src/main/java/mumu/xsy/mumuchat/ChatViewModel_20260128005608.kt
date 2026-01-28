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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.jsoup.Jsoup
import okio.Buffer
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Scriptable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
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
        private const val MAX_TOOL_CALLS_PER_TURN = 12
        private const val MAX_BROWSE_BYTES = 2 * 1024 * 1024L
        private const val MAX_BROWSE_REDIRECTS = 5
        private const val JS_MAX_RUNTIME_MS = 800L

        private const val CORE_SYSTEM_PROMPT = """
## 核心任务流 (ReAct 规范)
当你收到用户指令后，必须遵循以下内部逻辑：
1. **拆解 (Decompose)**: 将复杂问题拆分为多个子问题。
2. **推理 (Thought)**: 明确当前已知什么，还需要搜索什么。
3. **行动 (Action)**: 调用 `exa_search/browse_url/get_news_board/calculate/text_to_image` 或记忆工具（`get_memories/search_memories/upsert_memory`）来获取可靠信息。
4. **观察 (Observation)**: 分析搜索到的结果是否真实、是否有冲突。
5. **迭代 (Iterate)**: 如果结果不充分，继续调整关键词进行二轮搜索。
6. **总结 (Final Answer)**: 整合所有信息，给出详尽、诚实、无幻觉的回答。

## 搜索与工具使用准则
- **时效性优先**: 涉及新闻、数据、价格等，必须联网。
- **事实核查**: 对不确定的事实进行交叉验证。
- **工具静默**: 严禁在输出 `tool_calls` 的同时输出任何自然语言。
- **记忆更新**: 如果发现用户的偏好发生了变化，主动调用 `update_memory`。
- **结构化输出**: 工具返回为 JSON；需要复用字段时优先解析 JSON 而不是猜测文本。
- **索引约定**: 记忆 index 为 0 基索引。

## 回答风格
- 使用 Markdown 格式，层级分明。
- 引用搜索来源（如果有）。"""
    }

    private val gson = Gson()
    private val sessionsStore = SessionsStore(application, gson)
    private val settingsStore = SettingsStore(application, gson)
    private val exportService = ExportService()
    private val pdfExportService = PdfExportService()

    var settings by mutableStateOf(settingsStore.load().normalized())
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

    private val browseClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val rhinoFactory = object : ContextFactory() {
        override fun observeInstructionCount(cx: RhinoContext?, instructionCount: Int) {
            val deadline = cx?.getThreadLocal("deadline_ms") as? Long ?: return
            if (System.currentTimeMillis() > deadline) {
                throw RuntimeException("执行超时")
            }
        }
    }

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
        val normalized = newSettings.normalized()
        settings = normalized
        settingsStore.save(normalized)
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
            if (historyOfToolCalls.size >= MAX_TOOL_CALLS_PER_TURN) {
                withContext(Dispatchers.Main) {
                    updateStep(sIdx, aiMsgIndex, StepType.TOOL_CALL, "ERROR\n工具调用次数过多，已停止以避免循环")
                    updateMessageContent(sIdx, aiMsgIndex, "工具调用次数过多，已停止以避免循环。请换一种提问方式或减少需要工具的步骤。")
                    updateIsGenerating(false)
                    finalizeMessage(sIdx, aiMsgIndex)
                }
                return@launch
            }
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
                val memories = settings.memories.orEmpty()
                if (memories.isNotEmpty()) base + "\n\n用户记忆：\n" + memories.joinToString("\n") { "- $it" } else base
            }
            add(JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
            
            val history = sessions[sIdx].messages.orEmpty().dropLast(1)
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
                if (toolHistory.size >= MAX_TOOL_CALLS_PER_TURN) {
                    updateStep(sIdx, aiMsgIndex, StepType.TOOL_CALL, "ERROR\n工具调用次数过多，已停止以避免循环")
                    updateMessageContent(sIdx, aiMsgIndex, "工具调用次数过多，已停止以避免循环。")
                    updateIsGenerating(false)
                    finalizeMessage(sIdx, aiMsgIndex)
                    return@launch
                }
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
                    val func = call.getAsJsonObject("function")
                    val funcName = func.get("name")?.asString
                    val errJson = toolErr(funcName ?: "unknown_tool", "工具执行失败: ${e.message}")
                    toolHistory.add(call to errJson)
                    updateStep(sIdx, aiMsgIndex, StepType.TOOL_CALL, "ERROR\n$errJson", funcName)
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
                    return@executeToolWithRetry toolErr(name, "工具执行失败 (已重试 $maxRetries 次): ${e.message}")
                }
                delay(500) // 重试前等待
            }
        }
        return toolErr(name, "工具执行失败: 未知错误")
    }

    /**
     * 验证会话有效性
     */
    private fun isSessionValid(sIdx: Int, aiMsgIndex: Int): Boolean {
        if (sIdx >= sessions.size) {
            Log.w(TAG, "会话索引无效: sIdx=$sIdx, sessions.size=${sessions.size}")
            return false
        }
        val size = sessions[sIdx].messages.orEmpty().size
        if (aiMsgIndex >= size) {
            Log.w(TAG, "消息索引无效: aiMsgIndex=$aiMsgIndex, messages.size=$size")
            return false
        }
        return true
    }

    private suspend fun executeToolInternal(name: String, argsJson: String, sIdx: Int, aiMsgIndex: Int): String {
        // 验证参数
        if (argsJson.isBlank()) {
            Log.w(TAG, "工具参数为空: $name")
            return toolErr(name, "工具参数为空")
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
                        toolErr(name, "memory 内容不能为空")
                    } else {
                        withContext(Dispatchers.Main) { addMemory(fact) }
                        toolOk(name, JsonObject().apply {
                            addProperty("saved", true)
                            addProperty("preview", fact.trim().take(80))
                        })
                    }
                }
                "get_memories" -> {
                    toolOk(name, memoriesAsJson())
                }
                "search_memories" -> {
                    val query = args.get("query")?.asString ?: ""
                    if (query.isBlank()) {
                        toolErr(name, "query 不能为空")
                    } else {
                        toolOk(name, searchMemoriesAsJson(query))
                    }
                }
                "upsert_memory" -> {
                    val key = args.get("key")?.asString ?: ""
                    val value = args.get("value")?.asString ?: ""
                    if (key.isBlank() || value.isBlank()) {
                        toolErr(name, "key/value 不能为空")
                    } else {
                        withContext(Dispatchers.Main) { upsertMemory(key, value) }
                        toolOk(name, JsonObject().apply {
                            addProperty("key", key.trim())
                            addProperty("value", value.trim().take(120))
                        })
                    }
                }
                "delete_memory" -> {
                    val index = args.get("index")?.asInt
                    val size = settings.memories.orEmpty().size
                    if (index == null || index < 0 || index >= size) {
                        toolErr(name, "无效的记忆索引 $index (共 $size 条)")
                    } else {
                        withContext(Dispatchers.Main) { deleteMemory(index) }
                        toolOk(name, JsonObject().apply { addProperty("deleted_index", index) })
                    }
                }
                "update_memory" -> {
                    val index = args.get("index")?.asInt
                    val text = args.get("text")?.asString ?: ""
                    if (index == null || index < 0 || index >= settings.memories.orEmpty().size) {
                        toolErr(name, "无效的记忆索引 $index")
                    } else if (text.isBlank()) {
                        toolErr(name, "新内容不能为空")
                    } else {
                        withContext(Dispatchers.Main) { updateMemory(index, text) }
                        toolOk(name, JsonObject().apply { addProperty("updated_index", index) })
                    }
                }
                "search_chat_history" -> {
                    val query = args.get("query")?.asString ?: ""
                    val limit = args.get("limit")?.asInt ?: 6
                    if (query.isBlank()) {
                        toolErr(name, "query 不能为空")
                    } else {
                        toolOk(name, searchChatHistoryAsJson(sIdx, query, limit.coerceIn(1, 20)))
                    }
                }
                "exa_search" -> {
                    val query = args.get("query")?.asString ?: ""
                    if (query.isBlank()) {
                        toolErr(name, "搜索关键词不能为空")
                    } else if (settings.exaApiKey.isBlank()) {
                        toolErr(name, "未配置 Exa Search Key，请在设置中配置")
                    } else {
                        executeExaSearchSync(query)
                    }
                }
                "browse_url" -> {
                    val url = args.get("url")?.asString ?: ""
                    if (url.isBlank()) {
                        toolErr(name, "URL 不能为空")
                    } else if (!isValidUrl(url)) {
                        toolErr(name, "无效的 URL 格式")
                    } else {
                        val err = validateBrowseUrl(url)
                        if (err != null) toolErr(name, err) else executeBrowseUrlSync(url)
                    }
                }
                "calculate" -> {
                    val code = args.get("code")?.asString ?: ""
                    if (code.isBlank()) {
                        toolErr(name, "计算代码不能为空")
                    } else {
                        executeJsCalculate(code)
                    }
                }
                "text_to_image" -> {
                    val prompt = args.get("prompt")?.asString ?: ""
                    if (prompt.isBlank()) {
                        toolErr(name, "图片描述不能为空")
                    } else if (settings.apiKey.isBlank()) {
                        toolErr(name, "未配置 API Key")
                    } else {
                        executeTextToImageSync(prompt, sIdx, aiMsgIndex)
                    }
                }
                "get_news_board" -> {
                    val board = args.get("board")?.asString ?: ""
                    if (board.isBlank()) {
                        toolErr(name, "热搜板块不能为空")
                    } else {
                        executeGetNewsBoardSync(board)
                    }
                }
                else -> toolErr(name, "未知工具")
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeToolInternal 执行异常: $name", e)
            toolErr(name, "工具执行错误: ${e.message}")
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

    private fun validateBrowseUrl(url: String): String? {
        val httpUrl = url.trim().toHttpUrlOrNull() ?: return "无效的 URL"
        if (httpUrl.scheme != "http" && httpUrl.scheme != "https") return "仅支持 http/https"
        val host = httpUrl.host
        if (host.isBlank()) return "无效的 URL host"
        if (host.equals("localhost", ignoreCase = true)) return "禁止访问本机地址"
        if (host.endsWith(".local", ignoreCase = true)) return "禁止访问本地域名"
        val addrs = try {
            InetAddress.getAllByName(host).toList()
        } catch (_: Exception) {
            return "无法解析 host"
        }
        val blocked = addrs.any { addr ->
            addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
        }
        if (blocked) return "禁止访问内网/本机地址"
        return null
    }

    private fun fetchHtmlWithRedirects(startUrl: String): Pair<String, String> {
        var current = startUrl
        loop@ for (hop in 0..MAX_BROWSE_REDIRECTS) {
            val err = validateBrowseUrl(current)
            if (err != null) throw IllegalArgumentException(err)
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", "Mozilla/5.0 (Android; MuMuChat/2.1)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            browseClient.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val loc = response.header("Location") ?: throw IllegalStateException("重定向无 Location")
                    val next = response.request.url.resolve(loc)?.toString()
                        ?: throw IllegalStateException("无法解析重定向 URL")
                    current = next
                    if (hop == MAX_BROWSE_REDIRECTS) throw IllegalStateException("重定向次数过多")
                    return@use
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val source = response.body?.source() ?: throw IllegalStateException("空响应")
                val buffer = Buffer()
                while (buffer.size < MAX_BROWSE_BYTES) {
                    val toRead = minOf(8192L, MAX_BROWSE_BYTES - buffer.size)
                    val read = source.read(buffer, toRead)
                    if (read == -1L) break
                }
                val html = buffer.readUtf8()
                return current to html
            }
            if (hop < MAX_BROWSE_REDIRECTS) continue@loop
        }
        throw IllegalStateException("重定向次数过多")
    }

    private fun toolOk(tool: String, data: com.google.gson.JsonElement? = null, meta: JsonObject? = null): String {
        val obj = JsonObject().apply {
            addProperty("ok", true)
            addProperty("tool", tool)
            if (data != null) add("data", data)
            if (meta != null) add("meta", meta)
        }
        return gson.toJson(obj)
    }

    private fun toolErr(tool: String, message: String, code: String? = null): String {
        val obj = JsonObject().apply {
            addProperty("ok", false)
            addProperty("tool", tool)
            add("error", JsonObject().apply {
                addProperty("message", message)
                if (!code.isNullOrBlank()) addProperty("code", code)
            })
        }
        return gson.toJson(obj)
    }

    private fun memoriesAsJson(): JsonArray {
        val arr = JsonArray()
        settings.memories.orEmpty().forEachIndexed { idx, text ->
            arr.add(JsonObject().apply {
                addProperty("index", idx)
                addProperty("text", text)
            })
        }
        return arr
    }

    private fun searchMemoriesAsJson(query: String): JsonArray {
        val q = query.trim().lowercase(Locale.ROOT)
        val arr = JsonArray()
        settings.memories.orEmpty().forEachIndexed { idx, text ->
            if (text.lowercase(Locale.ROOT).contains(q)) {
                arr.add(JsonObject().apply {
                    addProperty("index", idx)
                    addProperty("text", text)
                })
            }
        }
        return arr
    }

    private fun searchChatHistoryAsJson(sIdx: Int, query: String, limit: Int): JsonArray {
        val q = query.trim().lowercase(Locale.ROOT)
        val arr = JsonArray()
        if (sIdx !in sessions.indices) return arr
        val messages = sessions[sIdx].messages.orEmpty()
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            val content = msg.content
            if (content.lowercase(Locale.ROOT).contains(q)) {
                val snippet = content.trim().replace("\n", " ").take(220)
                arr.add(JsonObject().apply {
                    addProperty("message_index", i)
                    addProperty("role", msg.role.name.lowercase(Locale.ROOT))
                    addProperty("snippet", snippet)
                })
                if (arr.size() >= limit) break
            }
        }
        return arr
    }

    private fun upsertMemory(key: String, value: String) {
        val k = key.trim().replace("\\s+".toRegex(), " ").take(60)
        val v = value.trim().replace("\\s+".toRegex(), " ").take(MAX_MEMORY_CHARS)
        if (k.isBlank() || v.isBlank()) return
        val line = "$k: $v"
        val prefix = "$k:"
        val updated = settings.memories.orEmpty().toMutableList()
        val idx = updated.indexOfFirst { it.trim().startsWith(prefix, ignoreCase = true) }
        if (idx >= 0) {
            updated[idx] = line
        } else {
            if (line !in updated) updated.add(line)
        }
        saveSettings(settings.copy(memories = updated.takeLast(MAX_MEMORIES)))
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
                return@use toolErr("get_news_board", "获取热搜失败: HTTP ${response.code}")
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Log.w(TAG, "获取热搜失败: 空响应")
                return@use toolErr("get_news_board", "获取热搜失败: 空响应")
            }

            Log.d(TAG, "热搜获取成功: ${body.length} 字符")
            val parsed = try {
                gson.fromJson(body, JsonObject::class.java)
            } catch (_: Exception) {
                null
            }
            toolOk("get_news_board", JsonObject().apply {
                addProperty("board", board)
                if (parsed != null) add("raw", parsed) else addProperty("raw", body)
            })
        }
    } catch (e: java.net.SocketTimeoutException) {
        Log.e(TAG, "获取热搜超时", e)
        toolErr("get_news_board", "获取热搜超时，请重试", code = "timeout")
    } catch (e: Exception) {
        Log.e(TAG, "获取热搜异常", e)
        toolErr("get_news_board", "获取热搜失败: ${e.message}")
    }

    private suspend fun executeTextToImageSync(prompt: String, sIdx: Int, aiMsgIndex: Int): String = try {
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
                return@use toolErr("text_to_image", "绘图失败: HTTP ${response.code}")
            }

            val bodyString = response.body?.string()
            if (bodyString.isNullOrBlank()) {
                Log.w(TAG, "生图失败: 空响应")
                return@use toolErr("text_to_image", "绘图失败: 空响应")
            }

            val jsonObject = try {
                gson.fromJson(bodyString, JsonObject::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "生图响应解析失败", e)
                return@use toolErr("text_to_image", "绘图失败: 响应解析错误")
            }

            val images = jsonObject.getAsJsonArray("images")
            if (images == null || images.size() == 0) {
                Log.w(TAG, "生图失败: 无 images 字段")
                return@use toolErr("text_to_image", "绘图失败: 无 images 字段")
            }

            val imageObj = images.get(0)?.asJsonObject
            val imageUrl = imageObj?.get("url")?.asString ?: imageObj?.get("b64_json")?.asString
            if (imageUrl == null) {
                Log.w(TAG, "生图失败: 无 url/b64_json 字段")
                return@use toolErr("text_to_image", "绘图失败: 无图片数据")
            }

            val finalImageUrl = if (imageUrl.startsWith("data:")) {
                "data:image/png;base64,${imageUrl.substringAfter("base64,")}"
            } else imageUrl
            withContext(Dispatchers.Main) {
                if (isSessionValid(sIdx, aiMsgIndex)) {
                    val updated = sessions[sIdx].messages.orEmpty().toMutableList()
                    updated[aiMsgIndex] = updated[aiMsgIndex].copy(imageUrl = finalImageUrl)
                    sessions[sIdx] = sessions[sIdx].copy(messages = updated)
                }
            }
            toolOk("text_to_image", JsonObject().apply {
                addProperty("status", "generated")
                addProperty("image_url", finalImageUrl)
                addProperty("prompt", prompt.take(200))
            })
        }
    } catch (e: java.net.SocketTimeoutException) {
        Log.e(TAG, "生图超时", e)
        toolErr("text_to_image", "生图超时，请重试", code = "timeout")
    } catch (e: Exception) {
        Log.e(TAG, "生图异常", e)
        toolErr("text_to_image", "绘图失败: ${e.message}")
    }

    private fun executeBrowseUrlSync(url: String): String = try {
        val err = validateBrowseUrl(url)
        if (err != null) toolErr("browse_url", err) else run {
            val (finalUrl, html) = fetchHtmlWithRedirects(url.trim())
            val doc = Jsoup.parse(html, finalUrl)
            val title = doc.title().takeIf { it.isNotBlank() } ?: "无标题"
            val text = (doc.select("article").first() ?: doc.body() ?: doc).text().trim()
            val maxChars = 12000
            val truncated = if (text.length > maxChars) text.take(maxChars) else text

            toolOk("browse_url", JsonObject().apply {
                addProperty("url", finalUrl)
                addProperty("title", title)
                addProperty("text", truncated)
                addProperty("truncated", text.length > maxChars)
                addProperty("text_length", text.length)
            })
        }
    } catch (e: java.net.SocketTimeoutException) {
        Log.e(TAG, "网页加载超时: $url", e)
        toolErr("browse_url", "网页加载超时，请检查网络或尝试其他 URL", code = "timeout")
    } catch (e: Exception) {
        Log.e(TAG, "网页浏览异常: $url", e)
        toolErr("browse_url", "浏览失败: ${e.message}")
    }

    private fun executeJsCalculate(code: String): String {
        var rhinoContext: RhinoContext? = null
        return try {
            rhinoContext = rhinoFactory.enterContext().apply {
                optimizationLevel = -1
                setClassShutter(ClassShutter { false })
                setInstructionObserverThreshold(10_000)
                putThreadLocal("deadline_ms", System.currentTimeMillis() + JS_MAX_RUNTIME_MS)
            }
            val scope = rhinoContext.initStandardObjects()

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

            toolOk("calculate", JsonObject().apply {
                addProperty("output", output.ifBlank { "" })
            })
        } catch (e: Exception) {
            Log.e(TAG, "JS 计算异常", e)
            val msg = e.message ?: "未知错误"
            val codeHint = when {
                msg.contains("ReferenceError") -> "ReferenceError"
                msg.contains("SyntaxError") -> "SyntaxError"
                msg.contains("TypeError") -> "TypeError"
                msg.contains("超时") -> "timeout"
                else -> null
            }
            toolErr("calculate", msg, code = codeHint)
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
                val msg = when (response.code) {
                    401 -> "API Key 无效"
                    429 -> "请求过于频繁，请稍后重试"
                    else -> "HTTP ${response.code}"
                }
                return@use toolErr("exa_search", "搜索失败: $msg", code = response.code.toString())
            }

            val bodyString = response.body?.string()
            if (bodyString.isNullOrBlank()) {
                Log.w(TAG, "Exa 搜索空响应")
                return@use toolErr("exa_search", "搜索失败: 空响应")
            }

            val jsonObject = try {
                gson.fromJson(bodyString, JsonObject::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Exa 响应解析失败", e)
                return@use toolErr("exa_search", "搜索失败: 响应解析错误")
            }

            val results = jsonObject.getAsJsonArray("results")
            val resultsCount = results?.size() ?: 0
            if (resultsCount == 0) {
                Log.w(TAG, "Exa 搜索无结果")
                return@use toolOk("exa_search", JsonArray())
            }

            val arr = JsonArray()
            results.forEachIndexed { index, result ->
                val obj = result.asJsonObject
                val title = obj.get("title")?.asString ?: "无标题"
                val url = obj.get("url")?.asString ?: ""
                val snippet = obj.get("description")?.asString ?: obj.get("text")?.asString ?: ""
                val snippetClean = snippet.take(280).replace("\n", " ").trim()
                arr.add(JsonObject().apply {
                    addProperty("rank", index + 1)
                    addProperty("title", title)
                    addProperty("url", url)
                    addProperty("snippet", snippetClean)
                })
            }

            Log.d(TAG, "Exa 搜索成功: $resultsCount 条结果")
            toolOk("exa_search", arr, meta = JsonObject().apply { addProperty("count", resultsCount) })
        }
    } catch (e: java.net.SocketTimeoutException) {
        Log.e(TAG, "Exa 搜索超时", e)
        toolErr("exa_search", "搜索超时，请重试", code = "timeout")
    } catch (e: Exception) {
        Log.e(TAG, "Exa 搜索异常", e)
        toolErr("exa_search", "搜索失败: ${e.message}")
    }

    private fun updateStep(sIdx: Int, msgIdx: Int, type: StepType, content: String, toolName: String? = null) {
        if (sIdx >= sessions.size) return
        val msg = sessions[sIdx].messages.orEmpty().getOrNull(msgIdx) ?: return
        val steps = msg.steps.orEmpty().toMutableList()
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
            val steps = msg.steps.orEmpty()
            steps.forEach { it.isFinished = true }
            msg.copy(steps = steps)
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
            val base = session.messages.orEmpty()
            val messages = if (msgIdx != null) {
                val msg = base.getOrNull(msgIdx) ?: return@launch
                base.toMutableList().apply {
                    this[msgIdx] = transform(msg)
                }
            } else {
                base.map { transform(it) }
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
