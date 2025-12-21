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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.ByteArrayOutputStream
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

    // === 持久化逻辑 ===
    private fun loadSettings(): AppSettings {
        val json = prefs.getString("settings", null)
        return if (json != null) {
            try {
                gson.fromJson(json, AppSettings::class.java)
            } catch (e: Exception) {
                AppSettings()
            }
        } else {
            AppSettings()
        }
    }

    private fun saveSettings(newSettings: AppSettings) {
        settings = newSettings
        prefs.edit().putString("settings", gson.toJson(newSettings)).apply()
    }

    fun updateSettings(newSettings: AppSettings) {
        saveSettings(newSettings)
    }

    fun addMemory(memory: String) {
        if (memory.isNotBlank()) {
            val updated = settings.copy(memories = settings.memories + memory)
            saveSettings(updated)
        }
    }

    // === 模型管理 ===
    fun isVisionModel(): Boolean {
        val model = settings.selectedModel.lowercase()
        return model.contains("vl") || model.contains("gemini") || 
               model.contains("internvl") || model.contains("vision") ||
               model.contains("omni") || model.contains("glm") ||
               model.contains("step") || model.contains("ocr")
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
                    if (!response.isSuccessful) return@launch
                    val body = response.body?.string() ?: return@launch
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val dataArray = json.getAsJsonArray("data")
                    val modelIds = dataArray.map { it.asJsonObject.get("id").asString }.sorted()
                    
                    val updated = settings.copy(fetchedModels = modelIds)
                    launch(Dispatchers.Main) { saveSettings(updated) }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // === 会话与发送逻辑 (保持不变) ===
    fun selectSession(id: String) { currentSessionId = id }
    fun createNewChat() {
        val newSession = ChatSession(title = "新对话 ${sessions.size + 1}")
        sessions.add(0, newSession)
        currentSessionId = newSession.id
    }
    fun editMessage(messageIndex: Int) {
        val sessionId = currentSessionId ?: return
        val sessionIndex = sessions.indexOfFirst { it.id == sessionId }
        if (sessionIndex == -1) return
        val currentSession = sessions[sessionIndex]
        if (messageIndex >= 0 && messageIndex < currentSession.messages.size) {
            inputDraft = currentSession.messages[messageIndex].content
            sessions[sessionIndex] = currentSession.copy(messages = currentSession.messages.take(messageIndex))
            stopGeneration()
        }
    }
    fun stopGeneration() {
        currentEventSource?.cancel()
        currentGenerationJob?.cancel()
    }

    fun sendMessage(context: Context, text: String) {
        val sessionId = currentSessionId ?: return
        if (text.isBlank() && selectedImageUri == null) return
        val sessionIndex = sessions.indexOfFirst { it.id == sessionId }
        if (sessionIndex == -1) return

        val imageBase64 = selectedImageUri?.let { uriToContent(context, it) }
        val finalImageUrl = imageBase64?.let { "data:image/jpeg;base64,$it" }

        val userMsg = ChatMessage(text, MessageRole.USER, imageUrl = finalImageUrl)
        sessions[sessionIndex] = sessions[sessionIndex].copy(
            messages = sessions[sessionIndex].messages + userMsg,
            title = if (sessions[sessionIndex].messages.isEmpty()) text.take(15) else sessions[sessionIndex].title
        )

        val aiMsgPlaceholder = ChatMessage("", MessageRole.ASSISTANT, reasoning = "")
        sessions[sessionIndex] = sessions[sessionIndex].copy(messages = sessions[sessionIndex].messages + aiMsgPlaceholder)
        val aiMsgIndex = sessions[sessionIndex].messages.size - 1

        selectedImageUri = null
        stopGeneration()
        
        currentGenerationJob = viewModelScope.launch(Dispatchers.IO) {
            val contentArray = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", text)
                })
                finalImageUrl?.let { url ->
                    add(JsonObject().apply {
                        addProperty("type", "image_url")
                        add("image_url", JsonObject().apply { addProperty("url", url) })
                    })
                }
            }

            val requestBody = JsonObject().apply {
                addProperty("model", settings.selectedModel)
                val messagesArray = JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        add("content", contentArray)
                    })
                }
                add("messages", messagesArray)
                addProperty("stream", true)
            }

            val request = Request.Builder()
                .url("${settings.baseUrl}/chat/completions")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            var accContent = ""
            var accReasoning = ""

            val listener = object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (data == "[DONE]") return
                    try {
                        val delta = gson.fromJson(data, JsonObject::class.java).getAsJsonArray("choices").get(0).asJsonObject.getAsJsonObject("delta")
                        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull) accReasoning += delta.get("reasoning_content").asString
                        if (delta.has("content") && !delta.get("content").isJsonNull) accContent += delta.get("content").asString
                        updateAiMessage(sessionIndex, aiMsgIndex, accContent, accReasoning)
                    } catch (e: Exception) {}
                }
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    updateAiMessage(sessionIndex, aiMsgIndex, "错误: ${t?.message}", null)
                }
            }
            currentEventSource = EventSources.createFactory(client).newEventSource(request, listener)
        }
    }

    private fun uriToContent(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }

    private fun updateAiMessage(sessionIndex: Int, msgIndex: Int, content: String, reasoning: String?) {
        if (sessionIndex >= sessions.size) return
        val currentSession = sessions[sessionIndex]
        if (msgIndex >= currentSession.messages.size) return
        val updatedMessages = currentSession.messages.toMutableList()
        updatedMessages[msgIndex] = updatedMessages[msgIndex].copy(content = content, reasoning = reasoning)
        sessions[sessionIndex] = currentSession.copy(messages = updatedMessages)
    }
}