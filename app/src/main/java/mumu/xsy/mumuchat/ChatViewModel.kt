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
                val request = Request.Builder().url("${settings.baseUrl}/models").header("Authorization", "Bearer ${settings.apiKey}").get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val ids = gson.fromJson(response.body?.string(), JsonObject::class.java).getAsJsonArray("data").map { it.asJsonObject.get("id").asString }
                        launch(Dispatchers.Main) { 
                            val mergedModels = (settings.availableModels + ids).distinct().sorted()
                            saveSettings(settings.copy(availableModels = mergedModels, fetchedModels = ids)) 
                        }
                    }
                }
            } catch (e: Exception) {}
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prompt = "总结一个2-5个字的对话标题，不要标点符号。用户说: \"$userFirstMsg\""
                val requestBody = JsonObject().apply {
                    addProperty("model", "deepseek-ai/DeepSeek-V3")
                    add("messages", JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt) }) })
                }
                val request = Request.Builder().url("${settings.baseUrl}/chat/completions").header("Authorization", "Bearer ${settings.apiKey}").post(requestBody.toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val title = gson.fromJson(response.body?.string(), JsonObject::class.java).getAsJsonArray("choices").get(0).asJsonObject.getAsJsonObject("message").get("content").asString.trim().replace("\"", "")
                        launch(Dispatchers.Main) { renameSession(sessions[sIdx].id, title) }
                    }
                }
            } catch (e: Exception) {}
        }
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
                            processAndContinue(sIdx, aiMsgIndex, activeToolCalls.values.toList(), historyOfToolCalls)
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
        val requestBody = JsonObject().apply { addProperty("model", "black-forest-labs/FLUX.1-schnell"); addProperty("prompt", prompt); addProperty("image_size", "1024x1024"); addProperty("num_inference_steps", 4) }
        client.newCall(Request.Builder().url("${settings.baseUrl}/images/generations").header("Authorization", "Bearer ${settings.apiKey}").post(requestBody.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            val imageUrl = gson.fromJson(response.body?.string(), JsonObject::class.java).getAsJsonArray("images").get(0).asJsonObject.get("url").asString
            viewModelScope.launch(Dispatchers.Main) {
                val updated = sessions[sIdx].messages.toMutableList()
                updated[aiMsgIndex] = updated[aiMsgIndex].copy(imageUrl = imageUrl)
                sessions[sIdx] = sessions[sIdx].copy(messages = updated)
            }
            "生成的图片已展示。"
        }
    } catch (e: Exception) { "绘图失败: ${e.message}" }

    private fun executeBrowseUrlSync(url: String): String = try {
        val doc = Jsoup.connect(url).timeout(10000).get()
        val text = (doc.select("article").first() ?: doc.body()).text()
        if (text.length > 8000) text.take(8000) + "..." else text
    } catch (e: Exception) { "浏览失败: ${e.message}" }

    private fun executeJsCalculate(code: String): String {
        val rhino = RhinoContext.enter().apply { optimizationLevel = -1 }
        return try { RhinoContext.toString(rhino.evaluateString(rhino.initStandardObjects(), code, "JS", 1, null)) } finally { RhinoContext.exit() }
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
        val msg = sessions[sIdx].messages[msgIdx]
        val steps = msg.steps.toMutableList()
        val existingIdx = steps.indexOfLast { it.type == type && it.toolName == toolName && !it.isFinished }
        if (existingIdx != -1) steps[existingIdx] = steps[existingIdx].copy(content = content)
        else { steps.forEach { it.isFinished = true }; steps.add(ChatStep(type, content, toolName)) }
        viewModelScope.launch(Dispatchers.Main) {
            val updated = sessions[sIdx].messages.toMutableList()
            updated[msgIdx] = msg.copy(steps = steps)
            sessions[sIdx] = sessions[sIdx].copy(messages = updated)
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
                val msg = sessions[sIdx].messages[msgIdx]; msg.steps.forEach { it.isFinished = true }
                val updated = sessions[sIdx].messages.toMutableList()
                updated[msgIdx] = msg.copy(steps = msg.steps)
                sessions[sIdx] = sessions[sIdx].copy(messages = updated)
            }
        }
    }

    private fun saveImageToInternalStorage(context: Context, uri: Uri): String? {
        return try {
            val bitmap = BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri)) ?: return null
            val file = File(context.cacheDir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
