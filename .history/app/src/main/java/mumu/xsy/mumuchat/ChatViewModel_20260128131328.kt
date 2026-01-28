package mumu.xsy.mumuchat

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.content.ContentValues
import android.Manifest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
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
import java.util.zip.ZipInputStream

import android.util.Log
import mumu.xsy.mumuchat.data.ActionsStore
import mumu.xsy.mumuchat.data.RecentActionEntry
import mumu.xsy.mumuchat.data.DocEntry
import mumu.xsy.mumuchat.data.DocsStore
import mumu.xsy.mumuchat.data.NoteEntry
import mumu.xsy.mumuchat.data.NotesStore
import mumu.xsy.mumuchat.data.ReminderEntry
import mumu.xsy.mumuchat.data.RemindersStore
import mumu.xsy.mumuchat.data.SessionsStore
import mumu.xsy.mumuchat.data.SettingsStore
import mumu.xsy.mumuchat.domain.ExportService
import mumu.xsy.mumuchat.domain.PdfExportService
import mumu.xsy.mumuchat.system.NotificationCenter
import mumu.xsy.mumuchat.system.MuMuKeepAliveService
import mumu.xsy.mumuchat.system.ReminderScheduler
import mumu.xsy.mumuchat.system.SystemIntents
import mumu.xsy.mumuchat.tools.BrowseSafety
import mumu.xsy.mumuchat.tools.RedirectFollower
import mumu.xsy.mumuchat.tools.TextTruncate
import mumu.xsy.mumuchat.tools.PublicApiRegistry
import mumu.xsy.mumuchat.tools.RemoteApiCache
import mumu.xsy.mumuchat.tools.FixedWindowRateLimiter
import mumu.xsy.mumuchat.tools.SimpleCircuitBreaker
import mumu.xsy.mumuchat.tools.RemoteApiClient
import mumu.xsy.mumuchat.tools.RemoteEndpoint
import mumu.xsy.mumuchat.tools.RemoteFetchResult
import mumu.xsy.mumuchat.tools.ToolsCatalog
import mumu.xsy.mumuchat.tools.mcp.McpException
import mumu.xsy.mumuchat.tools.mcp.McpManager
import mumu.xsy.mumuchat.tools.search.BaiduSerpProvider
import mumu.xsy.mumuchat.tools.search.DuckDuckGoSerpProvider
import mumu.xsy.mumuchat.tools.search.SearxngSerpProvider
import mumu.xsy.mumuchat.tools.search.SerpProviderException
import mumu.xsy.mumuchat.tools.search.SerpSearchService

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_MEMORIES = 80
        private const val MAX_MEMORY_CHARS = 200
        private const val MAX_TOOL_CALLS_PER_TURN = 150
        private const val MAX_BROWSE_BYTES = 2 * 1024 * 1024L
        private const val MAX_BROWSE_REDIRECTS = 5
        private const val JS_MAX_RUNTIME_MS = 800L
        private const val JS_MAX_OUTPUT_CHARS = 2000
        private const val TOOL_ERROR_BUFFER_SIZE = 50

        private const val CORE_SYSTEM_PROMPT = """
## 你的身份与目标
你是 MuMuChat 内置的多工具 AI 助手“木灵”。目标是：在最少工具调用次数下，产出高质量、可执行、可核查的结果，并在需要时自动调用工具完成检索/计算/整理/写入本地资料库等工作。

## 系统时间注入
系统会在本消息后续附加“当前系统时间”。你必须将其作为事实来源之一：涉及“今天/现在/近期/截止”等时间语义时，优先用该时间判断与表达，并在结论中体现时间基准（例如“截至 YYYY-MM-DD HH:mm（Asia/Shanghai）”）。

## 总体工作流（工具优先的规划-执行循环）
1. **澄清目标**：先判断用户要“答案/方案/产物/动作”。对产物型任务，先输出结构化计划再执行（必要时分步）。
2. **选择信息源**：优先使用本地可得信息（记忆/笔记/文档库/会话历史），再使用联网工具；能不联网就不联网。
3. **工具路由（优先级）**：
   - **本地检索**：`docs_search`（资料库）→ `search_notes`（笔记）→ `search_chat_history/search_sessions`（会话）。
   - **联网检索**：默认优先 `tavily_search`（结构化结果），需要更多候选链接时用 `serp_search`；需要阅读全文精读用 `browse_url`；`exa_search` 作为补充/备选。
   - **图像/音频理解**：图片包含文字用 `vision_ocr_url`；需要把音频转文字用 `audio_transcribe`。
   - **计算与生成**：公式/代码计算用 `calculate`；生图用 `text_to_image`。
   - **外部工具生态**：仅当配置了 MCP 且本地工具不足时，用 `mcp_list_servers/mcp_list_tools/mcp_call`。
   - **工具不确定时**：先调用 `list_tools` 获取可用工具与参数 schema，再决定下一步。
4. **执行与校验**：工具返回为 JSON，必须解析字段并做一致性检查；多来源信息要交叉验证，发现冲突要明确指出并给出最可信版本与理由。
5. **失败处理**：遇到失败先自查再重试：
   - 先用 `get_network_status/get_settings_summary` 判断网络或密钥配置；
   - 用 `get_last_tool_errors/get_tool_metrics` 定位失败原因；
   - 仍失败则切换到备选工具（例如 `tavily_search`→`serp_search`→`exa_search`），并把降级策略写进最终回答。
6. **授权与隐私**：涉及系统权限/文件选择/系统分享/日历/通知/外部 MCP 调用时，用户可能需要确认授权；不要在回答中泄露任何密钥或敏感信息。
7. **沉默规则**：当你输出 `tool_calls` 时，严禁同时输出任何自然语言内容。

## 记忆与资料库策略
- **长期偏好**：用 `save_memory/upsert_memory/update_memory` 维护用户偏好与稳定事实；避免重复与矛盾；记忆 index 为 0 基索引。
- **可复用资料**：把长文、整理后的要点、重要结论写入资料库 `docs_add_text`（可带 tags），后续优先 `docs_search` 复用，减少重复联网。
- **会话卫生**：需要复盘时可用 `summarize_session_local`；需要行动项时可用 `extract_todos_from_chat`。

## 输出要求（最佳效果）
- 默认使用 Markdown，结构清晰，给出可执行步骤与关键权衡。
- 当使用联网内容时：标注来源 URL 或明确“来自工具结果”。
- 严格避免编造：不确定就调用工具或明确“不确定/需要验证”。"""
    }

    private val gson = Gson()
    private val sessionsStore = SessionsStore(application, gson)
    private val settingsStore = SettingsStore(application, gson)
    private val notesStore = NotesStore(application, gson)
    private val docsStore = DocsStore(application, gson)
    private val actionsStore = ActionsStore(application, gson)
    private val remindersStore = RemindersStore(application, gson)
    private val reminderScheduler = ReminderScheduler(application)
    private val notificationCenter = NotificationCenter(application)
    private val exportService = ExportService()
    private val pdfExportService = PdfExportService()

    var settings by mutableStateOf(settingsStore.load().normalized())
        private set

    var sessions = mutableStateListOf<ChatSession>()
    var currentSessionId by mutableStateOf<String?>(null)
    var inputDraft by mutableStateOf("")
    var selectedImageUris = mutableStateListOf<Uri>()
    var selectedFileUris = mutableStateListOf<Uri>()

    var pendingUserApproval by mutableStateOf<PendingUserApproval?>(null)
        private set
    var pendingDocumentRequest by mutableStateOf<PendingDocumentRequest?>(null)
        private set

    private var pendingApprovalDeferred: CompletableDeferred<Boolean>? = null
    private var pendingDocumentDeferred: CompletableDeferred<Uri?>? = null

    var recentActions = mutableStateListOf<RecentActionEntry>()
        private set

    var reminders = mutableStateListOf<ReminderEntry>()
        private set

    var isKeepAliveRunning by mutableStateOf(false)
        private set
    private val keepAliveState = linkedMapOf<String, Pair<String, String>>()
    private var lastKeepAliveDisplay: Pair<String, String>? = null

    private var currentGenerationJob: Job? = null
    private var currentEventSource: EventSource? = null
    private var saveSessionsDebounceJob: Job? = null

    var isGenerating by mutableStateOf(false)
        private set

    private fun updateIsGenerating(value: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            isGenerating = value
            if (value) {
                val title = sessions.firstOrNull { it.id == currentSessionId }?.title?.trim().orEmpty().ifBlank { "新对话" }
                startKeepAlive("generation", "木木在思考", title)
            } else {
                stopKeepAlive("generation")
            }
        }
    }

    private fun startKeepAlive(reason: String, title: String, text: String) {
        keepAliveState[reason] = title to text
        val (t, x) = chooseKeepAliveDisplay()
        updateKeepAliveService(t, x)
    }

    private fun stopKeepAlive(reason: String) {
        keepAliveState.remove(reason)
        if (keepAliveState.isEmpty()) {
            val intent = Intent(getApplication(), MuMuKeepAliveService::class.java).apply {
                action = MuMuKeepAliveService.ACTION_STOP
            }
            runCatching { getApplication<Application>().startService(intent) }
            isKeepAliveRunning = false
            lastKeepAliveDisplay = null
        } else {
            val (t, x) = chooseKeepAliveDisplay()
            updateKeepAliveService(t, x)
        }
    }

    private fun updateKeepAliveService(title: String, text: String) {
        if (!canPostNotifications()) {
            isKeepAliveRunning = false
            return
        }
        val nextDisplay = title to text
        if (isKeepAliveRunning && lastKeepAliveDisplay == nextDisplay) return
        lastKeepAliveDisplay = nextDisplay
        val intent = Intent(getApplication(), MuMuKeepAliveService::class.java).apply {
            action = MuMuKeepAliveService.ACTION_START
            putExtra(MuMuKeepAliveService.EXTRA_TITLE, title)
            putExtra(MuMuKeepAliveService.EXTRA_TEXT, text)
            putExtra(MuMuKeepAliveService.EXTRA_ENABLE_SUPER_ISLAND, settings.enableSuperIsland == true)
        }
        val ok = runCatching {
            val app = getApplication<Application>()
            val inFg = AppForeground.inForeground
            if (isKeepAliveRunning) {
                app.startService(intent)
            } else {
                if (Build.VERSION.SDK_INT >= 26 && !inFg) app.startForegroundService(intent) else app.startService(intent)
            }
        }.isSuccess
        isKeepAliveRunning = ok
    }

    private fun chooseKeepAliveDisplay(): Pair<String, String> {
        val priority = listOf("generation", "browse", "file")
        for (k in priority) {
            keepAliveState[k]?.let { return it }
        }
        return keepAliveState.values.last()
    }

    fun handleAppAction(action: String?) {
        when (action) {
            Constants.Notifications.ACTION_STOP_GENERATION -> stopGeneration()
        }
    }

    fun getFocusProtocolVersion(): Int {
        return try {
            Settings.System.getInt(getApplication<Application>().contentResolver, "notification_focus_protocol", 0)
        } catch (_: Exception) {
            0
        }
    }

    fun canPostNotifications(): Boolean {
        val ctx = getApplication<Application>()
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun hasCalendarPermissions(): Boolean {
        val ctx = getApplication<Application>()
        val readOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val writeOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return readOk && writeOk
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

    private val redirectClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val mcpHttpClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val mcpManager = McpManager(mcpHttpClient)

    private val remoteApiClient = RemoteApiClient(
        baseClient = client,
        cache = RemoteApiCache(),
        limiter = FixedWindowRateLimiter(),
        breaker = SimpleCircuitBreaker()
    )

    private fun buildSerpProviders(): List<mumu.xsy.mumuchat.tools.search.SerpProvider> {
        val list = mutableListOf<mumu.xsy.mumuchat.tools.search.SerpProvider>()
        val searxng = settings.searxngBaseUrl.trim()
        if (searxng.isNotBlank()) list.add(SearxngSerpProvider(remoteApiClient, searxng))
        if (settings.enableSerpBaidu == true) list.add(BaiduSerpProvider(remoteApiClient))
        if (settings.enableSerpDuckDuckGo == true) list.add(DuckDuckGoSerpProvider(remoteApiClient))
        return list
    }

    private val rhinoFactory = object : ContextFactory() {
        override fun observeInstructionCount(cx: RhinoContext?, instructionCount: Int) {
            val deadline = cx?.getThreadLocal("deadline_ms") as? Long ?: return
            if (System.currentTimeMillis() > deadline) {
                throw RuntimeException("执行超时")
            }
        }
    }

    data class ToolMetricUi(
        val tool: String,
        val calls: Int,
        val errors: Int,
        val avgMs: Double,
        val totalMs: Long
    )

    data class ToolErrorUi(
        val tool: String,
        val message: String,
        val at: Long
    )

    data class PendingUserApproval(
        val id: String = java.util.UUID.randomUUID().toString(),
        val tool: String,
        val title: String,
        val detail: String? = null
    )

    data class PendingDocumentRequest(
        val id: String = java.util.UUID.randomUUID().toString(),
        val mode: String,
        val suggestedName: String? = null,
        val mimeTypes: List<String>? = null
    )

    private data class ToolStats(
        var calls: Int = 0,
        var errors: Int = 0,
        var totalMs: Long = 0L
    )

    private data class ToolErrorRecord(
        val tool: String,
        val message: String,
        val at: Long
    )

    private class ToolException(
        val code: String,
        override val message: String
    ) : RuntimeException(message)

    private inner class ToolEngine {
        private val handlers = mutableMapOf<String, suspend (JsonObject, Int, Int) -> String>()
        private val stats = mutableMapOf<String, ToolStats>()
        private val lastErrors = ArrayDeque<ToolErrorRecord>(TOOL_ERROR_BUFFER_SIZE)

        init {
            register("save_memory") { args, _, _ ->
                val fact = args.get("fact")?.asString ?: ""
                if (fact.isBlank()) toolErr("save_memory", "memory 内容不能为空") else {
                    withContext(Dispatchers.Main) { addMemory(fact) }
                    toolOk("save_memory", JsonObject().apply {
                        addProperty("saved", true)
                        addProperty("preview", fact.trim().take(80))
                    })
                }
            }
            register("get_memories") { _, _, _ -> toolOk("get_memories", memoriesAsJson()) }
            register("search_memories") { args, _, _ ->
                val query = args.get("query")?.asString ?: ""
                if (query.isBlank()) toolErr("search_memories", "query 不能为空") else toolOk("search_memories", searchMemoriesAsJson(query))
            }
            register("upsert_memory") { args, _, _ ->
                val key = args.get("key")?.asString ?: ""
                val value = args.get("value")?.asString ?: ""
                if (key.isBlank() || value.isBlank()) toolErr("upsert_memory", "key/value 不能为空") else {
                    withContext(Dispatchers.Main) { upsertMemory(key, value) }
                    toolOk("upsert_memory", JsonObject().apply {
                        addProperty("key", key.trim())
                        addProperty("value", value.trim().take(120))
                    })
                }
            }
            register("get_memory") { args, _, _ ->
                val key = args.get("key")?.asString
                val index = args.get("index")?.asInt
                val list = settings.memoriesV2.orEmpty()
                val entry = if (!key.isNullOrBlank()) {
                    list.firstOrNull { it.key?.equals(key.trim(), ignoreCase = true) == true }
                } else if (index != null && index in list.indices) {
                    list[index]
                } else null
                if (entry == null) {
                    toolErr("get_memory", "未找到记忆")
                } else {
                    toolOk("get_memory", JsonObject().apply {
                        addProperty("id", entry.id)
                        addProperty("key", entry.key)
                        addProperty("value", entry.value)
                        addProperty("updated_at", entry.updatedAt)
                    })
                }
            }
            register("delete_memory_by_key") { args, _, _ ->
                val key = args.get("key")?.asString ?: ""
                if (key.isBlank()) {
                    toolErr("delete_memory_by_key", "key 不能为空")
                } else {
                    val k = key.trim()
                    val list = settings.memoriesV2.orEmpty().toMutableList()
                    val idx = list.indexOfFirst { it.key?.equals(k, ignoreCase = true) == true }
                    if (idx < 0) {
                        toolErr("delete_memory_by_key", "未找到 key=$k 的记忆")
                    } else {
                        withContext(Dispatchers.Main) { deleteMemory(idx) }
                        toolOk("delete_memory_by_key", JsonObject().apply { addProperty("deleted_key", k) })
                    }
                }
            }
            register("list_memory_keys") { _, _, _ -> toolOk("list_memory_keys", listMemoryKeysAsJson()) }
            register("rename_memory_key") { args, _, _ ->
                val oldKey = args.get("old_key")?.asString ?: ""
                val newKey = args.get("new_key")?.asString ?: ""
                if (oldKey.isBlank() || newKey.isBlank()) {
                    toolErr("rename_memory_key", "old_key/new_key 不能为空")
                } else {
                    withContext(Dispatchers.Main) { renameMemoryKey(oldKey, newKey) }
                    toolOk("rename_memory_key", JsonObject().apply {
                        addProperty("old_key", oldKey.trim())
                        addProperty("new_key", newKey.trim())
                    })
                }
            }
            register("merge_memories") { _, _, _ ->
                withContext(Dispatchers.Main) { mergeMemories() }
                toolOk("merge_memories", JsonObject().apply { addProperty("merged", true) })
            }
            register("memory_gc") { args, _, _ ->
                val maxEntries = args.get("max_entries")?.asInt
                val keepRecentDays = args.get("keep_recent_days")?.asInt
                withContext(Dispatchers.Main) { memoryGc(maxEntries, keepRecentDays) }
                toolOk("memory_gc", JsonObject().apply {
                    addProperty("max_entries", maxEntries)
                    addProperty("keep_recent_days", keepRecentDays)
                    addProperty("memories_count", settings.memoriesV2.orEmpty().size)
                })
            }
            register("delete_memory") { args, _, _ ->
                val index = args.get("index")?.asInt
                val size = settings.memoriesV2.orEmpty().size
                if (index == null || index < 0 || index >= size) {
                    toolErr("delete_memory", "无效的记忆索引 $index (共 $size 条)")
                } else {
                    withContext(Dispatchers.Main) { deleteMemory(index) }
                    toolOk("delete_memory", JsonObject().apply { addProperty("deleted_index", index) })
                }
            }
            register("update_memory") { args, _, _ ->
                val index = args.get("index")?.asInt
                val text = args.get("text")?.asString ?: ""
                if (index == null || index < 0 || index >= settings.memoriesV2.orEmpty().size) {
                    toolErr("update_memory", "无效的记忆索引 $index")
                } else if (text.isBlank()) {
                    toolErr("update_memory", "新内容不能为空")
                } else {
                    withContext(Dispatchers.Main) { updateMemory(index, text) }
                    toolOk("update_memory", JsonObject().apply { addProperty("updated_index", index) })
                }
            }
            register("search_chat_history") { args, sIdx, _ ->
                val query = args.get("query")?.asString ?: ""
                val limit = args.get("limit")?.asInt ?: 6
                if (query.isBlank()) toolErr("search_chat_history", "query 不能为空")
                else toolOk("search_chat_history", searchChatHistoryAsJson(sIdx, query, limit.coerceIn(1, 20)))
            }
            register("summarize_session_local") { args, sIdx, _ ->
                val limit = (args.get("limit")?.asInt ?: 200).coerceIn(10, 2000)
                toolOk("summarize_session_local", summarizeSessionLocalAsJson(sIdx, limit))
            }
            register("extract_todos_from_chat") { args, sIdx, _ ->
                val limit = (args.get("limit")?.asInt ?: 200).coerceIn(10, 2000)
                toolOk("extract_todos_from_chat", extractTodosFromChatAsJson(sIdx, limit))
            }
            register("save_note") { args, _, _ ->
                val title = args.get("title")?.asString?.trim().orEmpty()
                val content = args.get("content")?.asString?.trim().orEmpty()
                val finalTitle = title.ifBlank { content.lines().firstOrNull().orEmpty().take(30) }.ifBlank { "note" }
                val entry = saveNote(finalTitle, content)
                toolOk("save_note", JsonObject().apply {
                    addProperty("id", entry.id)
                    addProperty("title", entry.title)
                    addProperty("updated_at", entry.updatedAt)
                })
            }
            register("list_notes") { args, _, _ ->
                val limit = (args.get("limit")?.asInt ?: 50).coerceIn(1, 200)
                toolOk("list_notes", listNotesAsJson(limit))
            }
            register("search_notes") { args, _, _ ->
                val query = args.get("query")?.asString ?: ""
                val limit = (args.get("limit")?.asInt ?: 20).coerceIn(1, 200)
                if (query.isBlank()) toolErr("search_notes", "query 不能为空")
                else toolOk("search_notes", searchNotesAsJson(query, limit))
            }
            register("delete_note") { args, _, _ ->
                val id = args.get("id")?.asString ?: ""
                if (id.isBlank()) toolErr("delete_note", "id 不能为空")
                else {
                    val ok = deleteNote(id)
                    if (!ok) toolErr("delete_note", "未找到笔记", code = "not_found")
                    else toolOk("delete_note", JsonObject().apply { addProperty("deleted", true) })
                }
            }
            register("docs_add_text") { args, _, _ ->
                val title = args.get("title")?.asString?.trim().orEmpty()
                val content = args.get("content")?.asString?.trim().orEmpty()
                val tagsRaw = args.get("tags")?.asString?.trim().orEmpty()
                if (content.isBlank()) toolErr("docs_add_text", "content 不能为空", code = "invalid_args")
                else {
                    val tags = tagsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20)
                    val finalTitle = title.ifBlank { content.lines().firstOrNull().orEmpty().take(30) }.ifBlank { "doc" }
                    val entry = addDoc(finalTitle, content, tags)
                    toolOk("docs_add_text", JsonObject().apply {
                        addProperty("id", entry.id)
                        addProperty("title", entry.title)
                        addProperty("updated_at", entry.updatedAt)
                    })
                }
            }
            register("docs_list") { args, _, _ ->
                val limit = (args.get("limit")?.asInt ?: 50).coerceIn(1, 200)
                toolOk("docs_list", listDocsAsJson(limit))
            }
            register("docs_search") { args, _, _ ->
                val query = args.get("query")?.asString?.trim().orEmpty()
                val limit = (args.get("limit")?.asInt ?: 20).coerceIn(1, 200)
                if (query.isBlank()) toolErr("docs_search", "query 不能为空", code = "invalid_args")
                else toolOk("docs_search", searchDocsAsJson(query, limit))
            }
            register("copy_to_clipboard") { args, _, _ ->
                val text = args.get("text")?.asString ?: ""
                if (text.isBlank()) toolErr("copy_to_clipboard", "text 不能为空")
                else if (settings.enableLocalTools != true) toolErr("copy_to_clipboard", "未启用本地动作工具", code = "disabled")
                else {
                    withContext(Dispatchers.Main) { copyToClipboard(text) }
                    toolOk("copy_to_clipboard", JsonObject().apply { addProperty("copied", true) })
                }
            }
            register("share_text") { args, _, _ ->
                val text = args.get("text")?.asString ?: ""
                val title = args.get("title")?.asString
                if (text.isBlank()) toolErr("share_text", "text 不能为空")
                else if (settings.enableLocalTools != true) toolErr("share_text", "未启用本地动作工具", code = "disabled")
                else {
                    withContext(Dispatchers.Main) { shareText(text, title) }
                    toolOk("share_text", JsonObject().apply { addProperty("shared", true) })
                }
            }
            register("search_sessions") { args, _, _ ->
                val query = args.get("query")?.asString ?: ""
                val limit = (args.get("limit")?.asInt ?: 10).coerceIn(1, 50)
                if (query.isBlank()) toolErr("search_sessions", "query 不能为空")
                else toolOk("search_sessions", searchSessionsAsJson(query, limit))
            }
            register("rename_session") { args, _, _ ->
                val sessionId = args.get("session_id")?.asString ?: ""
                val title = args.get("title")?.asString ?: ""
                if (sessionId.isBlank() || title.isBlank()) {
                    toolErr("rename_session", "session_id/title 不能为空")
                } else {
                    withContext(Dispatchers.Main) { renameSession(sessionId, title.trim()) }
                    toolOk("rename_session", JsonObject().apply {
                        addProperty("session_id", sessionId)
                        addProperty("title", title.trim())
                    })
                }
            }
            register("move_session_to_folder") { args, _, _ ->
                val sessionId = args.get("session_id")?.asString ?: ""
                val folder = args.get("folder")?.asString
                if (sessionId.isBlank()) {
                    toolErr("move_session_to_folder", "session_id 不能为空")
                } else {
                    withContext(Dispatchers.Main) { moveSessionToFolder(sessionId, folder?.trim()?.takeIf { it.isNotBlank() }) }
                    toolOk("move_session_to_folder", JsonObject().apply {
                        addProperty("session_id", sessionId)
                        addProperty("folder", folder?.trim())
                    })
                }
            }
            register("export_session") { args, sIdx, _ ->
                val sessionId = args.get("session_id")?.asString
                val format = (args.get("format")?.asString ?: "markdown").trim().lowercase(Locale.ROOT)
                val session = if (!sessionId.isNullOrBlank()) {
                    sessions.firstOrNull { it.id == sessionId }
                } else {
                    sessions.getOrNull(sIdx)
                }
                if (session == null) {
                    toolErr("export_session", "未找到会话")
                } else {
                    withContext(Dispatchers.IO) {
                        when (format) {
                            "markdown" -> toolOk("export_session", JsonObject().apply {
                                addProperty("format", "markdown")
                                addProperty("session_id", session.id)
                                addProperty("text", exportService.exportSessionToMarkdown(session))
                            })
                            "plain", "text", "plaintext" -> toolOk("export_session", JsonObject().apply {
                                addProperty("format", "plain")
                                addProperty("session_id", session.id)
                                addProperty("text", exportService.exportSessionToPlainText(session))
                            })
                            "html" -> toolOk("export_session", JsonObject().apply {
                                addProperty("format", "html")
                                addProperty("session_id", session.id)
                                addProperty("text", exportService.exportSessionToHtml(session))
                            })
                            "pdf" -> {
                                val text = exportService.exportSessionToPlainText(session)
                                val file = pdfExportService.exportTextToPdfFile(getApplication(), session.title, text)
                                if (file == null) {
                                    toolErr("export_session", "PDF 导出失败", code = "export_failed")
                                } else {
                                    toolOk("export_session", JsonObject().apply {
                                        addProperty("format", "pdf")
                                        addProperty("session_id", session.id)
                                        addProperty("file_path", file.absolutePath)
                                        addProperty("file_size", file.length())
                                    })
                                }
                            }
                            else -> toolErr("export_session", "不支持的格式: $format", code = "invalid_format")
                        }
                    }
                }
            }
            register("exa_search") { args, _, _ ->
                val query = args.get("query")?.asString ?: ""
                if (query.isBlank()) toolErr("exa_search", "搜索关键词不能为空")
                else if (settings.exaApiKey.isBlank()) toolErr("exa_search", "未配置 Exa Search Key，请在设置中配置")
                else executeExaSearchSync(query)
            }
            register("tavily_search") { args, _, _ ->
                val query = args.get("query")?.asString ?: ""
                val maxResults = (args.get("max_results")?.asInt ?: 8).coerceIn(1, 20)
                val searchDepth = args.get("search_depth")?.asString?.trim()?.ifBlank { "basic" } ?: "basic"
                if (query.isBlank()) toolErr("tavily_search", "搜索关键词不能为空")
                else if (settings.tavilyApiKey.isBlank()) toolErr("tavily_search", "未配置 Tavily Key，请在设置中配置")
                else executeTavilySearchSync(query, maxResults, searchDepth)
            }
            register("browse_url") { args, _, _ ->
                val url = args.get("url")?.asString ?: ""
                if (url.isBlank()) toolErr("browse_url", "URL 不能为空")
                else if (!isValidUrl(url)) toolErr("browse_url", "无效的 URL 格式")
                else {
                    val r = validateBrowseUrl(url)
                    if (!r.ok) toolErr("browse_url", r.message ?: "浏览被阻止", r.code) else executeBrowseUrlSync(url)
                }
            }
            register("serp_search") { args, _, _ ->
                val query = args.get("query")?.asString ?: ""
                val engine = args.get("engine")?.asString
                val limit = (args.get("limit")?.asInt ?: 8).coerceIn(1, 10)
                val page = (args.get("page")?.asInt ?: 1).coerceIn(1, 50)
                val site = args.get("site")?.asString
                val resolveRedirects = args.get("resolve_redirects")?.asBoolean == true
                if (query.isBlank()) toolErr("serp_search", "query 不能为空")
                else if (settings.enableSerpSearch != true) toolErr("serp_search", "未启用 SERP 搜索", code = "disabled")
                else {
                    try {
                        val normalizedEngine = engine?.trim()?.lowercase(Locale.ROOT)
                        if (!normalizedEngine.isNullOrBlank() && normalizedEngine != "auto") {
                            val ok = when (normalizedEngine) {
                                "baidu" -> settings.enableSerpBaidu == true
                                "duckduckgo", "ddg" -> settings.enableSerpDuckDuckGo == true
                                "searxng", "searx" -> settings.searxngBaseUrl.trim().isNotBlank()
                                else -> true
                            }
                            if (!ok) return@register toolErr("serp_search", "未启用搜索引擎: $normalizedEngine", code = "disabled")
                        }

                        val enabledProviders = buildSerpProviders()
                        if (enabledProviders.isEmpty()) return@register toolErr("serp_search", "未启用任何 SERP 搜索引擎", code = "disabled")
                        val service = SerpSearchService(enabledProviders)

                        val r = service.search(
                            query = query,
                            engine = engine,
                            limit = limit,
                            page = page,
                            site = site
                        )
                        val resultsArr = JsonArray()
                        var resolvedCount = 0
                        r.results.forEach { item ->
                            var url = item.url
                            var originalUrl: String? = null
                            if (resolveRedirects && isValidUrl(url)) {
                                val rr = resolveRedirectFinalUrl(url)
                                if (rr.ok && !rr.finalUrl.isNullOrBlank() && rr.finalUrl != url) {
                                    originalUrl = url
                                    url = rr.finalUrl
                                    resolvedCount += 1
                                }
                            }
                            resultsArr.add(JsonObject().apply {
                                addProperty("rank", item.rank)
                                addProperty("title", item.title)
                                addProperty("url", url)
                                if (!originalUrl.isNullOrBlank()) addProperty("original_url", originalUrl)
                                if (!item.displayUrl.isNullOrBlank()) addProperty("display_url", item.displayUrl)
                                if (!item.snippet.isNullOrBlank()) addProperty("snippet", item.snippet)
                                addProperty("engine", item.engine)
                            })
                        }
                        val data = JsonObject().apply { add("results", resultsArr) }
                        val meta = JsonObject().apply {
                            addProperty("engine", r.engine)
                            addProperty("count", r.results.size)
                            addProperty("resolve_redirects", resolveRedirects)
                            if (resolveRedirects) addProperty("resolved_count", resolvedCount)
                            if (r.latencyMs != null) addProperty("latency_ms", r.latencyMs)
                            if (r.cached != null) addProperty("cached", r.cached)
                            if (!r.downgradedFrom.isNullOrBlank()) addProperty("downgraded_from", r.downgradedFrom)
                        }
                        toolOk("serp_search", data, meta)
                    } catch (e: SerpProviderException) {
                        val hint = when (e.code) {
                            "connect_failed" -> "（网络连接失败；国内环境建议启用百度或配置可访问的 SearXNG 地址）"
                            "captcha" -> "（搜索引擎触发验证；建议换 engine=searxng 或稍后重试）"
                            else -> null
                        }
                        toolErr("serp_search", e.message + (hint ?: ""), e.code)
                    } catch (e: Exception) {
                        toolErr("serp_search", e.message ?: "搜索失败", code = "search_failed")
                    }
                }
            }
            register("mcp_list_servers") { _, _, _ ->
                if (settings.enableMcpTools != true) return@register toolErr("mcp_list_servers", "未启用 MCP", code = "disabled")
                val arr = JsonArray()
                settings.mcpServers.orEmpty().forEach { s ->
                    if (s.endpoint.isBlank()) return@forEach
                    arr.add(JsonObject().apply {
                        addProperty("id", s.id)
                        addProperty("endpoint", s.endpoint)
                        addProperty("has_token", !s.authToken.isNullOrBlank())
                    })
                }
                toolOk("mcp_list_servers", JsonObject().apply { add("servers", arr) })
            }
            register("mcp_list_tools") { args, _, _ ->
                val serverId = args.get("server_id")?.asString?.trim().orEmpty()
                if (serverId.isBlank()) return@register toolErr("mcp_list_tools", "server_id 不能为空", code = "invalid_args")
                if (settings.enableMcpTools != true) return@register toolErr("mcp_list_tools", "未启用 MCP", code = "disabled")
                val cfg = settings.mcpServers.orEmpty().firstOrNull { it.id == serverId }
                    ?: return@register toolErr("mcp_list_tools", "未找到 MCP server: $serverId", code = "not_found")
                if (!isValidUrl(cfg.endpoint)) return@register toolErr("mcp_list_tools", "无效的 endpoint", code = "invalid_url")
                try {
                    val result = withContext(Dispatchers.IO) { mcpManager.get(cfg).listTools() }
                    toolOk("mcp_list_tools", JsonObject().apply {
                        addProperty("server_id", serverId)
                        add("result", result)
                    })
                } catch (e: McpException) {
                    toolErr("mcp_list_tools", e.message, e.code)
                } catch (e: Exception) {
                    toolErr("mcp_list_tools", e.message ?: "请求失败", code = "mcp_failed")
                }
            }
            register("mcp_call") { args, _, _ ->
                val serverId = args.get("server_id")?.asString?.trim().orEmpty()
                val name = args.get("name")?.asString?.trim().orEmpty()
                val arguments = args.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject
                if (serverId.isBlank() || name.isBlank()) return@register toolErr("mcp_call", "server_id/name 不能为空", code = "invalid_args")
                if (settings.enableMcpTools != true) return@register toolErr("mcp_call", "未启用 MCP", code = "disabled")
                val cfg = settings.mcpServers.orEmpty().firstOrNull { it.id == serverId }
                    ?: return@register toolErr("mcp_call", "未找到 MCP server: $serverId", code = "not_found")
                if (!isValidUrl(cfg.endpoint)) return@register toolErr("mcp_call", "无效的 endpoint", code = "invalid_url")
                val approved = requestUserApproval(
                    tool = "mcp_call",
                    title = "调用 MCP 工具：$serverId::$name",
                    detail = "endpoint: ${cfg.endpoint}"
                )
                if (!approved) {
                    appendRecentAction(RecentActionEntry(tool = "mcp_call", summary = "$serverId::$name", approved = false, status = "denied"))
                    return@register toolErr("mcp_call", "用户已拒绝", code = "user_denied")
                }
                try {
                    val result = withContext(Dispatchers.IO) { mcpManager.get(cfg).callTool(name, arguments) }
                    appendRecentAction(RecentActionEntry(tool = "mcp_call", summary = "$serverId::$name", approved = true, status = "ok"))
                    toolOk("mcp_call", JsonObject().apply {
                        addProperty("server_id", serverId)
                        addProperty("name", name)
                        add("result", result)
                    })
                } catch (e: McpException) {
                    appendRecentAction(RecentActionEntry(tool = "mcp_call", summary = "$serverId::$name", approved = true, status = e.code))
                    toolErr("mcp_call", e.message, e.code)
                } catch (e: Exception) {
                    appendRecentAction(RecentActionEntry(tool = "mcp_call", summary = "$serverId::$name", approved = true, status = "failed"))
                    toolErr("mcp_call", e.message ?: "请求失败", code = "mcp_failed")
                }
            }
            register("calendar_create_event") { args, _, _ ->
                val title = args.get("title")?.asString?.trim().orEmpty()
                val startMs = args.get("start_ms")?.asLong
                val endMs = args.get("end_ms")?.asLong
                val location = args.get("location")?.asString?.trim()
                val notes = args.get("notes")?.asString?.trim()
                if (settings.enableCalendarTools != true) return@register toolErr("calendar_create_event", "未启用日历工具", code = "disabled")
                if (title.isBlank()) return@register toolErr("calendar_create_event", "title 不能为空")
                if (startMs == null || endMs == null || startMs <= 0L || endMs <= 0L || endMs <= startMs) {
                    return@register toolErr("calendar_create_event", "start_ms/end_ms 无效", code = "invalid_time")
                }
                val approved = requestUserApproval(
                    tool = "calendar_create_event",
                    title = "创建日历事件：$title",
                    detail = "开始：$startMs\n结束：$endMs"
                )
                if (!approved) {
                    appendRecentAction(RecentActionEntry(tool = "calendar_create_event", summary = title, approved = false, status = "denied"))
                    return@register toolErr("calendar_create_event", "用户已拒绝", code = "user_denied")
                }
                val r = withContext(Dispatchers.Main) {
                    val ctx = getApplication<Application>()
                    SystemIntents.startActivityBestEffort(
                        context = ctx,
                        intent = SystemIntents.buildInsertCalendarEventIntent(
                            title = title,
                            startMs = startMs,
                            endMs = endMs,
                            location = location,
                            notes = notes
                        ),
                        preferredPackages = SystemIntents.preferredCalendarPackages
                    )
                }
                if (!r.started) {
                    appendRecentAction(RecentActionEntry(tool = "calendar_create_event", summary = title, approved = true, status = "failed", detail = r.error))
                    return@register toolErr("calendar_create_event", "无法打开系统日历（可能无可用日历应用）", code = "no_handler")
                }
                appendRecentAction(RecentActionEntry(tool = "calendar_create_event", summary = title, approved = true, status = "started", detail = r.resolved?.packageName))
                toolOk("calendar_create_event", JsonObject().apply {
                    addProperty("started", true)
                    if (r.resolved != null) {
                        addProperty("package", r.resolved.packageName)
                        addProperty("activity", r.resolved.className)
                    }
                })
            }
            register("calendar_insert_silent") { args, _, _ ->
                val title = args.get("title")?.asString?.trim().orEmpty()
                val startMs = args.get("start_ms")?.asLong
                val endMs = args.get("end_ms")?.asLong
                val location = args.get("location")?.asString?.trim()
                val notes = args.get("notes")?.asString?.trim()
                if (settings.enableCalendarTools != true) return@register toolErr("calendar_insert_silent", "未启用日历工具", code = "disabled")
                if (title.isBlank()) return@register toolErr("calendar_insert_silent", "title 不能为空")
                if (startMs == null || endMs == null || startMs <= 0L || endMs <= 0L || endMs <= startMs) {
                    return@register toolErr("calendar_insert_silent", "start_ms/end_ms 无效", code = "invalid_time")
                }
                if (!hasCalendarPermissions()) return@register toolErr("calendar_insert_silent", "日历权限未开启", code = "permission_denied")
                val approved = requestUserApproval(
                    tool = "calendar_insert_silent",
                    title = "写入日历事件：$title",
                    detail = "开始：$startMs\n结束：$endMs"
                )
                if (!approved) {
                    appendRecentAction(RecentActionEntry(tool = "calendar_insert_silent", summary = title, approved = false, status = "denied"))
                    return@register toolErr("calendar_insert_silent", "用户已拒绝", code = "user_denied")
                }
                val inserted = withContext(Dispatchers.IO) { insertCalendarEventSilent(title, startMs, endMs, location, notes) }
                if (inserted == null) {
                    appendRecentAction(RecentActionEntry(tool = "calendar_insert_silent", summary = title, approved = true, status = "failed"))
                    toolErr("calendar_insert_silent", "写入失败", code = "insert_failed")
                } else {
                    val (eventId, calendarId) = inserted
                    appendRecentAction(RecentActionEntry(tool = "calendar_insert_silent", summary = title, approved = true, status = "created", detail = eventId))
                    toolOk("calendar_insert_silent", JsonObject().apply {
                        addProperty("event_id", eventId)
                        addProperty("calendar_id", calendarId)
                    })
                }
            }
            register("calendar_list_events") { args, _, _ ->
                val startMs = args.get("start_ms")?.asLong
                val endMs = args.get("end_ms")?.asLong
                val limit = (args.get("limit")?.asInt ?: 20).coerceIn(1, 50)
                if (settings.enableCalendarTools != true) return@register toolErr("calendar_list_events", "未启用日历工具", code = "disabled")
                if (startMs == null || endMs == null || startMs <= 0L || endMs <= 0L || endMs <= startMs) {
                    return@register toolErr("calendar_list_events", "start_ms/end_ms 无效", code = "invalid_time")
                }
                if (!hasCalendarPermissions()) return@register toolErr("calendar_list_events", "日历权限未开启", code = "permission_denied")
                val approved = requestUserApproval(
                    tool = "calendar_list_events",
                    title = "读取日历事件列表",
                    detail = "范围：$startMs ~ $endMs"
                )
                if (!approved) {
                    appendRecentAction(RecentActionEntry(tool = "calendar_list_events", summary = "range", approved = false, status = "denied"))
                    return@register toolErr("calendar_list_events", "用户已拒绝", code = "user_denied")
                }
                val items = withContext(Dispatchers.IO) { listCalendarEvents(startMs, endMs, limit) }
                appendRecentAction(RecentActionEntry(tool = "calendar_list_events", summary = "list:$limit", approved = true, status = "ok"))
                toolOk("calendar_list_events", JsonObject().apply { add("events", items) })
            }
            register("calendar_delete_event") { args, _, _ ->
                val eventId = args.get("event_id")?.asString?.trim().orEmpty()
                if (settings.enableCalendarTools != true) return@register toolErr("calendar_delete_event", "未启用日历工具", code = "disabled")
                if (eventId.isBlank()) return@register toolErr("calendar_delete_event", "event_id 不能为空")
                if (!hasCalendarPermissions()) return@register toolErr("calendar_delete_event", "日历权限未开启", code = "permission_denied")
                val approved = requestUserApproval(
                    tool = "calendar_delete_event",
                    title = "删除日历事件",
                    detail = "event_id: $eventId"
                )
                if (!approved) {
                    appendRecentAction(RecentActionEntry(tool = "calendar_delete_event", summary = eventId, approved = false, status = "denied"))
                    return@register toolErr("calendar_delete_event", "用户已拒绝", code = "user_denied")
                }
                val ok = withContext(Dispatchers.IO) { deleteCalendarEvent(eventId) }
                appendRecentAction(RecentActionEntry(tool = "calendar_delete_event", summary = eventId, approved = true, status = if (ok) "deleted" else "not_found"))
                if (!ok) toolErr("calendar_delete_event", "未删除（可能不存在）", code = "not_found")
                else toolOk("calendar_delete_event", JsonObject().apply { addProperty("deleted", true) })
            }
            register("notify_set_timer") { args, _, _ ->
                val seconds = args.get("seconds")?.asInt
                val message = args.get("message")?.asString?.trim()
                if (settings.enableNotificationTools != true) return@register toolErr("notify_set_timer", "未启用通知/提醒工具", code = "disabled")
                if (seconds == null || seconds <= 0) return@register toolErr("notify_set_timer", "seconds 无效", code = "invalid_seconds")
                val approved = requestUserApproval(
                    tool = "notify_set_timer",
                    title = "设置倒计时提醒",
                    detail = "秒数：$seconds\n消息：${message.orEmpty()}"
                )
                if (!approved) {
                    appendRecentAction(RecentActionEntry(tool = "notify_set_timer", summary = "timer:$seconds", approved = false, status = "denied"))
                    return@register toolErr("notify_set_timer", "用户已拒绝", code = "user_denied")
                }
                val r = withContext(Dispatchers.Main) {
                    val ctx = getApplication<Application>()
                    val fast = SystemIntents.startActivityBestEffort(
                        context = ctx,
                        intent = SystemIntents.buildSetTimerIntent(seconds = seconds, message = message, skipUi = true),
                        preferredPackages = SystemIntents.preferredClockPackages
                    )
                    if (fast.started) fast else SystemIntents.startActivityBestEffort(
                        context = ctx,
                        intent = SystemIntents.buildSetTimerIntent(seconds = seconds, message = message, skipUi = false),
                        preferredPackages = SystemIntents.preferredClockPackages
                    )
                }
                if (!r.started) {
                    appendRecentAction(RecentActionEntry(tool = "notify_set_timer", summary = "timer:$seconds", approved = true, status = "failed", detail = r.error))
                    val candidates = SystemIntents.resolveCandidates(getApplication(), SystemIntents.buildSetTimerIntent(seconds = seconds, message = message, skipUi = false))
                    val detail = if (candidates.isEmpty()) null else candidates.joinToString("\n") { "${it.packageName}/${it.className}" }
                    return@register toolErr(
                        "notify_set_timer",
                        "未找到可处理系统计时器的应用或被系统拦截" + (if (!detail.isNullOrBlank()) "\n可用处理器:\n$detail" else ""),
                        code = "no_handler"
                    )
                }
                appendRecentAction(RecentActionEntry(tool = "notify_set_timer", summary = "timer:$seconds", approved = true, status = "started", detail = r.resolved?.packageName))
                toolOk("notify_set_timer", JsonObject().apply {
                    addProperty("started", true)
                    if (r.resolved != null) {
                        addProperty("package", r.resolved.packageName)
                        addProperty("activity", r.resolved.className)
                    }
                })
            }
            register("notify_schedule_at") { args, _, _ ->
                val title = args.get("title")?.asString?.trim().orEmpty()
                val text = args.get("text")?.asString?.trim()
                val atMs = args.get("at_ms")?.asLong
                if (settings.enableNotificationTools != true) return@register toolErr("notify_schedule_at", "未启用通知/提醒工具", code = "disabled")
                if (title.isBlank()) return@register toolErr("notify_schedule_at", "title 不能为空")
                if (atMs == null || atMs <= 0L) return@register toolErr("notify_schedule_at", "at_ms 无效", code = "invalid_time")
                if (!canPostNotifications()) return@register toolErr("notify_schedule_at", "通知权限未开启", code = "permission_denied")

                val approved = requestUserApproval(
                    tool = "notify_schedule_at",
                    title = "创建定时提醒：$title",
                    detail = "触发时间(ms)：$atMs"
                )
                if (!approved) {
                    appendRecentAction(RecentActionEntry(tool = "notify_schedule_at", summary = title, approved = false, status = "denied"))
                    return@register toolErr("notify_schedule_at", "用户已拒绝", code = "user_denied")
                }

                val entry = ReminderEntry(atMs = atMs, title = title, text = text)
                withContext(Dispatchers.Main) { scheduleReminderInternal(entry) }
                appendRecentAction(RecentActionEntry(tool = "notify_schedule_at", summary = title, approved = true, status = "scheduled", detail = entry.id))
                toolOk("notify_schedule_at", JsonObject().apply {
                    addProperty("id", entry.id)
                    addProperty("title", title)
                    addProperty("at_ms", atMs)
                })
            }
            register("notify_cancel_scheduled") { args, _, _ ->
                val id = args.get("id")?.asString?.trim().orEmpty()
                if (settings.enableNotificationTools != true) return@register toolErr("notify_cancel_scheduled", "未启用通知/提醒工具", code = "disabled")
                if (id.isBlank()) return@register toolErr("notify_cancel_scheduled", "id 不能为空")
                val approved = requestUserApproval(
                    tool = "notify_cancel_scheduled",
                    title = "取消定时提醒",
                    detail = "id: $id"
                )
                if (!approved) {
                    appendRecentAction(RecentActionEntry(tool = "notify_cancel_scheduled", summary = id, approved = false, status = "denied"))
                    return@register toolErr("notify_cancel_scheduled", "用户已拒绝", code = "user_denied")
                }
                withContext(Dispatchers.Main) { cancelReminderInternal(id) }
                appendRecentAction(RecentActionEntry(tool = "notify_cancel_scheduled", summary = id, approved = true, status = "canceled"))
                toolOk("notify_cancel_scheduled", JsonObject().apply { addProperty("canceled", true) })
            }
            register("list_scheduled_reminders") { _, _, _ ->
                val arr = JsonArray()
                reminders.sortedBy { it.atMs }.forEach { r ->
                    arr.add(JsonObject().apply {
                        addProperty("id", r.id)
                        addProperty("at_ms", r.atMs)
                        addProperty("title", r.title)
                        if (!r.text.isNullOrBlank()) addProperty("text", r.text)
                    })
                }
                toolOk("list_scheduled_reminders", JsonObject().apply { add("reminders", arr) })
            }
            register("file_export_session") { args, sIdx, _ ->
                if (settings.enableFileTools != true) return@register toolErr("file_export_session", "未启用文件工具", code = "disabled")
                val sessionId = args.get("session_id")?.asString
                val session = if (!sessionId.isNullOrBlank()) {
                    sessions.firstOrNull { it.id == sessionId }
                } else {
                    sessions.getOrNull(sIdx)
                } ?: return@register toolErr("file_export_session", "未找到会话", code = "not_found")

                val approved = requestUserApproval(
                    tool = "file_export_session",
                    title = "导出会话为 JSON 文件",
                    detail = "会话：${session.title}\n消息数：${session.messages.orEmpty().size}"
                )
                if (!approved) {
                    appendRecentAction(RecentActionEntry(tool = "file_export_session", summary = session.title, approved = false, status = "denied"))
                    return@register toolErr("file_export_session", "用户已拒绝", code = "user_denied")
                }

                val suggestedName = "${sanitizeFilename(session.title)}.json"
                startKeepAlive("file", "会话导出中", session.title.take(60))
                val uri = requestCreateJsonDocument(suggestedName)
                if (uri == null) {
                    stopKeepAlive("file")
                    appendRecentAction(RecentActionEntry(tool = "file_export_session", summary = session.title, approved = true, status = "canceled"))
                    return@register toolErr("file_export_session", "用户已取消保存", code = "user_canceled")
                }

                val json = gson.toJson(session)
                val ok = withContext(Dispatchers.IO) {
                    try {
                        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(json.toByteArray(Charsets.UTF_8))
                            os.flush()
                        } ?: return@withContext false
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                if (!ok) {
                    stopKeepAlive("file")
                    appendRecentAction(RecentActionEntry(tool = "file_export_session", summary = session.title, approved = true, status = "failed"))
                    return@register toolErr("file_export_session", "写入文件失败", code = "write_failed")
                }
                stopKeepAlive("file")
                appendRecentAction(RecentActionEntry(tool = "file_export_session", summary = session.title, approved = true, status = "saved", detail = uri.toString()))
                toolOk("file_export_session", JsonObject().apply {
                    addProperty("session_id", session.id)
                    addProperty("title", session.title)
                    addProperty("uri", uri.toString())
                })
            }
            register("file_import_session") { _, _, _ ->
                if (settings.enableFileTools != true) return@register toolErr("file_import_session", "未启用文件工具", code = "disabled")
                val approved = requestUserApproval(
                    tool = "file_import_session",
                    title = "从文件导入会话 JSON",
                    detail = null
                )
                if (!approved) {
                    appendRecentAction(RecentActionEntry(tool = "file_import_session", summary = "import", approved = false, status = "denied"))
                    return@register toolErr("file_import_session", "用户已拒绝", code = "user_denied")
                }
                startKeepAlive("file", "会话导入中", "等待选择文件")
                val uri = requestOpenDocument(listOf("application/json", "text/plain", "*/*"))
                if (uri == null) {
                    stopKeepAlive("file")
                    appendRecentAction(RecentActionEntry(tool = "file_import_session", summary = "import", approved = true, status = "canceled"))
                    return@register toolErr("file_import_session", "用户已取消选择文件", code = "user_canceled")
                }

                val text = withContext(Dispatchers.IO) {
                    try {
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (text == null) {
                    stopKeepAlive("file")
                    return@register toolErr("file_import_session", "读取文件失败", code = "read_failed")
                }

                val imported = try { gson.fromJson(text, ChatSession::class.java) } catch (_: Exception) { null }
                if (imported == null) {
                    stopKeepAlive("file")
                    return@register toolErr("file_import_session", "文件格式不受支持（需要会话 JSON）", code = "invalid_format")
                }

                val newSession = normalizeSession(
                    imported.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        title = imported.title.ifBlank { "导入会话" }
                    )
                )
                withContext(Dispatchers.Main) {
                    sessions.add(newSession)
                    currentSessionId = newSession.id
                    saveSessionsToDataStore()
                }
                stopKeepAlive("file")
                appendRecentAction(RecentActionEntry(tool = "file_import_session", summary = newSession.title, approved = true, status = "imported", detail = uri.toString()))
                toolOk("file_import_session", JsonObject().apply {
                    addProperty("session_id", newSession.id)
                    addProperty("title", newSession.title)
                })
            }
            register("calculate") { args, _, _ ->
                val code = args.get("code")?.asString ?: ""
                if (code.isBlank()) toolErr("calculate", "计算代码不能为空") else executeJsCalculate(code)
            }
            register("text_to_image") { args, sIdx, aiMsgIndex ->
                val prompt = args.get("prompt")?.asString ?: ""
                if (prompt.isBlank()) toolErr("text_to_image", "图片描述不能为空")
                else if (settings.apiKey.isBlank()) toolErr("text_to_image", "未配置 API Key")
                else executeTextToImageSync(prompt, sIdx, aiMsgIndex)
            }
            register("vision_ocr_url") { args, _, _ ->
                val imageUrl = args.get("image_url")?.asString?.trim().orEmpty()
                val langHint = args.get("lang_hint")?.asString?.trim()
                val model = args.get("model")?.asString?.trim()
                if (imageUrl.isBlank()) toolErr("vision_ocr_url", "image_url 不能为空", code = "invalid_args")
                else if (!isValidUrl(imageUrl)) toolErr("vision_ocr_url", "无效的 image_url", code = "invalid_url")
                else if (settings.apiKey.isBlank()) toolErr("vision_ocr_url", "未配置 API Key")
                else executeVisionOcrUrlSync(imageUrl, langHint, model)
            }
            register("audio_transcribe") { args, _, _ ->
                val language = args.get("language")?.asString?.trim()
                val prompt = args.get("prompt")?.asString?.trim()
                if (settings.apiKey.isBlank()) return@register toolErr("audio_transcribe", "未配置 API Key")
                if (settings.enableFileTools != true) return@register toolErr("audio_transcribe", "未启用文件工具", code = "disabled")
                val approved = requestUserApproval("audio_transcribe", "选择音频文件并转写", "将打开文件选择器以选择音频文件")
                if (!approved) return@register toolErr("audio_transcribe", "用户已拒绝", code = "user_denied")
                val uri = requestOpenDocument(listOf("audio/*"))
                if (uri == null) return@register toolErr("audio_transcribe", "用户已取消选择", code = "user_canceled")
                executeAudioTranscribeSync(uri, language, prompt)
            }
            register("get_news_board") { args, _, _ ->
                val board = args.get("board")?.asString ?: ""
                if (board.isBlank()) toolErr("get_news_board", "热搜板块不能为空") else executeGetNewsBoardSync(board)
            }
            register("get_daily_brief") { args, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("get_daily_brief", "未启用公益 API", code = "disabled")
                val source = (args.get("source")?.asString ?: "viki").trim().lowercase(Locale.ROOT)
                val format = (args.get("format")?.asString ?: "json").trim().lowercase(Locale.ROOT)
                if (source == "viki" && settings.enablePublicApiViki != true) return@register toolErr("get_daily_brief", "未启用 60s.viki.moe", code = "disabled")
                if (source == "vvhan" && settings.enablePublicApiVvHan != true) return@register toolErr("get_daily_brief", "未启用 vvhan.com", code = "disabled")
                if (source == "qqsuu" && settings.enablePublicApiQqsuu != true) return@register toolErr("get_daily_brief", "未启用 qqsuu.cn", code = "disabled")
                if (source == "770a" && settings.enablePublicApi770a != true) return@register toolErr("get_daily_brief", "未启用 770a.cn", code = "disabled")
                val endpointId = when (source) {
                    "viki" -> when (format) {
                        "text" -> "60s_viki_text"
                        "image", "image_url" -> "60s_viki_image"
                        else -> "60s_viki_json"
                    }
                    "114128", "mirror" -> "60s_114128_json"
                    "vvhan" -> {
                        if (format != "image" && format != "image_url") return@register toolErr("get_daily_brief", "vvhan 仅支持图片格式", code = "invalid_format")
                        "60s_vvhan_image"
                    }
                    "qqsuu" -> {
                        if (format != "image" && format != "image_url") return@register toolErr("get_daily_brief", "qqsuu 仅支持图片格式", code = "invalid_format")
                        "60s_qqsuu_image"
                    }
                    "770a" -> {
                        if (format != "image" && format != "image_url") return@register toolErr("get_daily_brief", "770a 仅支持图片格式", code = "invalid_format")
                        "60s_770a_image"
                    }
                    else -> "60s_viki_json"
                }
                val endpoint = PublicApiRegistry.find(endpointId)
                    ?: return@register toolErr("get_daily_brief", "未知端点: $endpointId", code = "unknown_endpoint")

                if (format == "image" || format == "image_url") {
                    val urlResult = resolveRedirectFinalUrl(endpoint.url)
                    if (!urlResult.ok) {
                        toolErr("get_daily_brief", urlResult.message ?: "获取失败", code = urlResult.code)
                    } else {
                        toolOk("get_daily_brief", JsonObject().apply {
                            addProperty("source", source)
                            addProperty("format", "image_url")
                            addProperty("endpoint_id", endpointId)
                            addProperty("final_url", urlResult.finalUrl)
                        })
                    }
                } else {
                    val r = remoteApiClient.fetch(endpoint)
                    if (!r.ok) {
                        toolErr("get_daily_brief", r.message ?: "获取失败", code = r.code)
                    } else {
                        val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                        toolOk("get_daily_brief", JsonObject().apply {
                            addProperty("source", source)
                            addProperty("format", format)
                            addProperty("endpoint_id", endpointId)
                            addProperty("http_code", r.httpCode)
                            addProperty("latency_ms", r.latencyMs)
                            if (parsed != null) add("raw", parsed) else addProperty("raw", r.body ?: "")
                        })
                    }
                }
            }
            register("probe_public_api") { args, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("probe_public_api", "未启用公益 API", code = "disabled")
                val id = args.get("endpoint_id")?.asString?.trim()
                val endpoints = if (!id.isNullOrBlank()) {
                    listOfNotNull(PublicApiRegistry.find(id))
                } else {
                    PublicApiRegistry.endpoints
                }
                val arr = JsonArray()
                endpoints.forEach { ep ->
                    val r = remoteApiClient.fetch(ep, bypassCache = true)
                    arr.add(JsonObject().apply {
                        addProperty("endpoint_id", ep.id)
                        addProperty("url", ep.url)
                        addProperty("ok", r.ok)
                        addProperty("code", r.code)
                        addProperty("http_code", r.httpCode)
                        addProperty("latency_ms", r.latencyMs)
                    })
                }
                toolOk("probe_public_api", JsonObject().apply { add("results", arr) })
            }
            register("get_hotlist") { args, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("get_hotlist", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiTenApi != true) return@register toolErr("get_hotlist", "未启用 TenAPI", code = "disabled")
                val platform = args.get("platform")?.asString?.trim()?.lowercase(Locale.ROOT) ?: ""
                val endpointId = when (platform) {
                    "weibo" -> "tenapi_weibohot"
                    "zhihu" -> "tenapi_zhihuhot"
                    "douyin" -> "tenapi_douyinhot"
                    "baidu" -> "tenapi_baiduhot"
                    "toutiao" -> "tenapi_toutiaohot"
                    "bilibili", "bili" -> "tenapi_bilibilihot"
                    else -> return@register toolErr("get_hotlist", "不支持的平台: $platform", code = "invalid_platform")
                }
                val endpoint = PublicApiRegistry.find(endpointId) ?: return@register toolErr("get_hotlist", "端点不存在: $endpointId", code = "unknown_endpoint")
                val r = remoteApiClient.fetch(endpoint)
                if (!r.ok) toolErr("get_hotlist", r.message ?: "获取失败", code = r.code) else {
                    val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                    toolOk("get_hotlist", JsonObject().apply {
                        addProperty("platform", platform)
                        addProperty("endpoint_id", endpointId)
                        addProperty("http_code", r.httpCode)
                        addProperty("latency_ms", r.latencyMs)
                        if (parsed != null) add("raw", parsed) else addProperty("raw", r.body ?: "")
                    })
                }
            }
            register("get_yiyan") { _, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("get_yiyan", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiTenApi != true) return@register toolErr("get_yiyan", "未启用 TenAPI", code = "disabled")
                val endpoint = PublicApiRegistry.find("tenapi_yiyan") ?: return@register toolErr("get_yiyan", "端点不存在", code = "unknown_endpoint")
                val r = remoteApiClient.fetch(endpoint)
                if (!r.ok) toolErr("get_yiyan", r.message ?: "获取失败", code = r.code) else {
                    val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                    toolOk("get_yiyan", JsonObject().apply {
                        addProperty("endpoint_id", endpoint.id)
                        addProperty("http_code", r.httpCode)
                        addProperty("latency_ms", r.latencyMs)
                        if (parsed != null) add("raw", parsed) else addProperty("text", (r.body ?: "").trim())
                    })
                }
            }
            register("public_translate") { args, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("public_translate", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiViki != true) return@register toolErr("public_translate", "未启用 60s.viki.moe", code = "disabled")
                val text = args.get("text")?.asString ?: ""
                val to = (args.get("to")?.asString ?: "en").trim()
                if (text.isBlank()) return@register toolErr("public_translate", "text 不能为空", code = "invalid_args")
                val url = "https://60s.viki.moe/v2/fanyi".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("text", text)
                    ?.addQueryParameter("to", to)
                    ?.build()
                    ?.toString()
                    ?: return@register toolErr("public_translate", "URL 构造失败", code = "invalid_url")
                val r = fetchPublicEndpointDynamic("viki_fanyi", url, ttlMs = 60 * 60_000L, maxPerMinute = 60)
                if (!r.ok) toolErr("public_translate", r.message ?: "获取失败", code = r.code) else {
                    val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                    toolOk("public_translate", JsonObject().apply {
                        addProperty("to", to)
                        addProperty("http_code", r.httpCode)
                        if (parsed != null) add("raw", parsed) else addProperty("raw", r.body ?: "")
                    })
                }
            }
            register("public_weather") { args, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("public_weather", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiViki != true) return@register toolErr("public_weather", "未启用 60s.viki.moe", code = "disabled")
                val query = args.get("query")?.asString ?: ""
                if (query.isBlank()) return@register toolErr("public_weather", "query 不能为空", code = "invalid_args")
                val url = "https://60s.viki.moe/v2/weather".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("query", query)
                    ?.build()
                    ?.toString()
                    ?: return@register toolErr("public_weather", "URL 构造失败", code = "invalid_url")
                val r = fetchPublicEndpointDynamic("viki_weather", url, ttlMs = 10 * 60_000L, maxPerMinute = 60)
                if (!r.ok) toolErr("public_weather", r.message ?: "获取失败", code = r.code) else {
                    val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                    toolOk("public_weather", if (parsed != null) parsed else JsonObject().apply { addProperty("raw", r.body ?: "") })
                }
            }
            register("public_exchange_rates") { args, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("public_exchange_rates", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiViki != true) return@register toolErr("public_exchange_rates", "未启用 60s.viki.moe", code = "disabled")
                val c = (args.get("c")?.asString ?: "CNY").trim().uppercase(Locale.ROOT)
                val url = "https://60s.viki.moe/v2/ex-rates".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("c", c)
                    ?.build()
                    ?.toString()
                    ?: return@register toolErr("public_exchange_rates", "URL 构造失败", code = "invalid_url")
                val r = fetchPublicEndpointDynamic("viki_ex_rates", url, ttlMs = 60 * 60_000L, maxPerMinute = 60)
                if (!r.ok) toolErr("public_exchange_rates", r.message ?: "获取失败", code = r.code) else {
                    val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                    toolOk("public_exchange_rates", JsonObject().apply {
                        addProperty("c", c)
                        if (parsed != null) add("raw", parsed) else addProperty("raw", r.body ?: "")
                    })
                }
            }
            register("public_today_in_history") { _, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("public_today_in_history", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiViki != true) return@register toolErr("public_today_in_history", "未启用 60s.viki.moe", code = "disabled")
                val r = fetchPublicEndpointDynamic("viki_today_in_history", "https://60s.viki.moe/v2/today_in_history", ttlMs = 24 * 60 * 60_000L, maxPerMinute = 30)
                if (!r.ok) toolErr("public_today_in_history", r.message ?: "获取失败", code = r.code) else {
                    val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                    toolOk("public_today_in_history", parsed ?: JsonObject().apply { addProperty("raw", r.body ?: "") })
                }
            }
            register("public_bing_wallpaper") { _, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("public_bing_wallpaper", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiViki != true) return@register toolErr("public_bing_wallpaper", "未启用 60s.viki.moe", code = "disabled")
                val url = "https://60s.viki.moe/v2/bing?encoding=text"
                val r = fetchPublicEndpointDynamic("viki_bing", url, ttlMs = 6 * 60 * 60_000L, maxPerMinute = 30, maxBytes = 256 * 1024L)
                if (!r.ok) toolErr("public_bing_wallpaper", r.message ?: "获取失败", code = r.code) else toolOk("public_bing_wallpaper", JsonObject().apply { addProperty("text", (r.body ?: "").trim()) })
            }
            register("public_epic_free_games") { _, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("public_epic_free_games", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiViki != true) return@register toolErr("public_epic_free_games", "未启用 60s.viki.moe", code = "disabled")
                val r = fetchPublicEndpointDynamic("viki_epic", "https://60s.viki.moe/v2/epic", ttlMs = 60 * 60_000L, maxPerMinute = 30)
                if (!r.ok) toolErr("public_epic_free_games", r.message ?: "获取失败", code = r.code) else {
                    val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                    toolOk("public_epic_free_games", parsed ?: JsonObject().apply { addProperty("raw", r.body ?: "") })
                }
            }
            register("public_maoyan_boxoffice") { _, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("public_maoyan_boxoffice", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiViki != true) return@register toolErr("public_maoyan_boxoffice", "未启用 60s.viki.moe", code = "disabled")
                val r = fetchPublicEndpointDynamic("viki_maoyan", "https://60s.viki.moe/v2/maoyan", ttlMs = 10 * 60_000L, maxPerMinute = 30)
                if (!r.ok) toolErr("public_maoyan_boxoffice", r.message ?: "获取失败", code = r.code) else {
                    val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                    toolOk("public_maoyan_boxoffice", parsed ?: JsonObject().apply { addProperty("raw", r.body ?: "") })
                }
            }
            register("ip_info") { args, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("ip_info", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiVvHan != true) return@register toolErr("ip_info", "未启用 vvhan", code = "disabled")
                val ip = args.get("ip")?.asString ?: ""
                if (ip.isBlank()) return@register toolErr("ip_info", "ip 不能为空", code = "invalid_args")
                val url = "https://api.vvhan.com/api/ipInfo".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("ip", ip)
                    ?.build()
                    ?.toString()
                    ?: return@register toolErr("ip_info", "URL 构造失败", code = "invalid_url")
                val r = fetchPublicEndpointDynamic("vvhan_ipinfo", url, ttlMs = 24 * 60 * 60_000L, maxPerMinute = 60)
                if (!r.ok) toolErr("ip_info", r.message ?: "获取失败", code = r.code) else {
                    val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                    toolOk("ip_info", parsed ?: JsonObject().apply { addProperty("raw", r.body ?: "") })
                }
            }
            register("icp_lookup") { args, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("icp_lookup", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiVvHan != true) return@register toolErr("icp_lookup", "未启用 vvhan", code = "disabled")
                val domain = args.get("domain")?.asString ?: ""
                if (domain.isBlank()) return@register toolErr("icp_lookup", "domain 不能为空", code = "invalid_args")
                val url = "https://api.vvhan.com/api/icp".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("url", domain)
                    ?.build()
                    ?.toString()
                    ?: return@register toolErr("icp_lookup", "URL 构造失败", code = "invalid_url")
                val r = fetchPublicEndpointDynamic("vvhan_icp", url, ttlMs = 24 * 60 * 60_000L, maxPerMinute = 60)
                if (!r.ok) toolErr("icp_lookup", r.message ?: "获取失败", code = r.code) else {
                    val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                    toolOk("icp_lookup", parsed ?: JsonObject().apply { addProperty("raw", r.body ?: "") })
                }
            }
            register("phone_info") { args, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("phone_info", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiVvHan != true) return@register toolErr("phone_info", "未启用 vvhan", code = "disabled")
                val tel = args.get("tel")?.asString ?: ""
                if (tel.isBlank()) return@register toolErr("phone_info", "tel 不能为空", code = "invalid_args")
                val url = "https://api.vvhan.com/api/phone".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("tel", tel)
                    ?.build()
                    ?.toString()
                    ?: return@register toolErr("phone_info", "URL 构造失败", code = "invalid_url")
                val r = fetchPublicEndpointDynamic("vvhan_phone", url, ttlMs = 24 * 60 * 60_000L, maxPerMinute = 60)
                if (!r.ok) toolErr("phone_info", r.message ?: "获取失败", code = r.code) else {
                    val parsed = try { gson.fromJson(r.body, JsonObject::class.java) } catch (_: Exception) { null }
                    toolOk("phone_info", parsed ?: JsonObject().apply { addProperty("raw", r.body ?: "") })
                }
            }
            register("qr_url") { args, _, _ ->
                if (settings.enablePublicApis != true) return@register toolErr("qr_url", "未启用公益 API", code = "disabled")
                if (settings.enablePublicApiVvHan != true) return@register toolErr("qr_url", "未启用 vvhan", code = "disabled")
                val text = args.get("text")?.asString ?: ""
                if (text.isBlank()) return@register toolErr("qr_url", "text 不能为空", code = "invalid_args")
                val url = "https://api.vvhan.com/api/qr".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("text", text)
                    ?.build()
                    ?.toString()
                    ?: return@register toolErr("qr_url", "URL 构造失败", code = "invalid_url")
                toolOk("qr_url", JsonObject().apply { addProperty("image_url", url) })
            }
            register("get_session_stats") { args, sIdx, _ ->
                val limit = (args.get("limit")?.asInt ?: 200).coerceIn(1, 2000)
                val session = sessions.getOrNull(sIdx) ?: return@register toolErr("get_session_stats", "无效会话")
                val msgs = session.messages.orEmpty().takeLast(limit)
                val byRole = JsonObject().apply {
                    var user = 0
                    var assistant = 0
                    var system = 0
                    var tool = 0
                    msgs.forEach { m ->
                        when (m.role) {
                            MessageRole.USER -> user++
                            MessageRole.ASSISTANT -> assistant++
                            MessageRole.SYSTEM -> system++
                            MessageRole.TOOL -> tool++
                        }
                    }
                    addProperty("user", user)
                    addProperty("assistant", assistant)
                    addProperty("system", system)
                    addProperty("tool", tool)
                }
                val minTs = msgs.minOfOrNull { it.timestamp }
                val maxTs = msgs.maxOfOrNull { it.timestamp }
                toolOk("get_session_stats", JsonObject().apply {
                    addProperty("session_id", session.id)
                    addProperty("title", session.title)
                    addProperty("folder", session.folder)
                    addProperty("messages_count", msgs.size)
                    add("by_role", byRole)
                    addProperty("from_ts", minTs)
                    addProperty("to_ts", maxTs)
                })
            }
            register("list_tools") { _, _, _ -> toolOk("list_tools", ToolsCatalog.getToolsDefinition()) }
            register("get_tool_metrics") { _, _, _ -> toolOk("get_tool_metrics", metricsAsJson()) }
            register("clear_tool_metrics") { _, _, _ ->
                stats.clear()
                lastErrors.clear()
                toolOk("clear_tool_metrics", JsonObject().apply { addProperty("cleared", true) })
            }
            register("get_last_tool_errors") { args, _, _ ->
                val limit = (args.get("limit")?.asInt ?: 20).coerceIn(1, TOOL_ERROR_BUFFER_SIZE)
                toolOk("get_last_tool_errors", lastToolErrorsAsJson(limit))
            }
            register("get_network_status") { _, _, _ -> toolOk("get_network_status", networkStatusAsJson()) }
            register("get_settings_summary") { _, _, _ -> toolOk("get_settings_summary", settingsSummaryAsJson()) }
            register("get_browse_policy") { _, _, _ -> toolOk("get_browse_policy", browsePolicyAsJson()) }
            register("set_browse_policy") { args, _, _ ->
                val allow = args.get("allowlist")?.takeIf { it.isJsonArray }?.asJsonArray
                val deny = args.get("denylist")?.takeIf { it.isJsonArray }?.asJsonArray
                withContext(Dispatchers.Main) { setBrowsePolicy(allow, deny) }
                toolOk("set_browse_policy", browsePolicyAsJson())
            }
        }

        private fun register(name: String, handler: suspend (JsonObject, Int, Int) -> String) {
            handlers[name] = handler
        }

        private fun toolTimeoutMs(name: String): Long? {
            return when (name) {
                "exa_search" -> 12_000L
                "browse_url" -> 20_000L
                "get_news_board" -> 10_000L
                "get_daily_brief" -> 10_000L
                "probe_public_api" -> 12_000L
                "get_hotlist" -> 10_000L
                "get_yiyan" -> 8_000L
                "public_translate" -> 10_000L
                "public_weather" -> 10_000L
                "public_exchange_rates" -> 10_000L
                "public_today_in_history" -> 10_000L
                "public_bing_wallpaper" -> 10_000L
                "public_epic_free_games" -> 10_000L
                "public_maoyan_boxoffice" -> 10_000L
                "ip_info" -> 10_000L
                "icp_lookup" -> 10_000L
                "phone_info" -> 10_000L
                "qr_url" -> 5_000L
                "text_to_image" -> 90_000L
                "calculate" -> 2_000L
                "get_network_status" -> 8_000L
                else -> 5_000L
            }
        }

        suspend fun execute(name: String, args: JsonObject, sIdx: Int, aiMsgIndex: Int): String {
            val handler = handlers[name] ?: return toolErr(name, "未知工具")
            val start = SystemClock.elapsedRealtime()
            return try {
                val timeoutMs = toolTimeoutMs(name)
                val result = if (timeoutMs != null) withTimeout(timeoutMs) { handler(args, sIdx, aiMsgIndex) } else handler(args, sIdx, aiMsgIndex)
                record(name, ok = true, elapsedMs = SystemClock.elapsedRealtime() - start)
                result
            } catch (e: TimeoutCancellationException) {
                record(name, ok = false, elapsedMs = SystemClock.elapsedRealtime() - start, errorMessage = "timeout")
                toolErr(name, "超时", code = "timeout")
            } catch (e: ToolException) {
                record(name, ok = false, elapsedMs = SystemClock.elapsedRealtime() - start, errorMessage = e.message)
                toolErr(name, e.message, code = e.code)
            } catch (e: Exception) {
                val msg = e.message ?: "未知错误"
                record(name, ok = false, elapsedMs = SystemClock.elapsedRealtime() - start, errorMessage = msg)
                toolErr(name, "工具执行错误: $msg", code = "tool_error")
            }
        }

        private fun record(name: String, ok: Boolean, elapsedMs: Long, errorMessage: String? = null) {
            val s = stats.getOrPut(name) { ToolStats() }
            s.calls += 1
            if (!ok) s.errors += 1
            s.totalMs += elapsedMs
            if (!ok && !errorMessage.isNullOrBlank()) {
                if (lastErrors.size >= TOOL_ERROR_BUFFER_SIZE) lastErrors.removeFirst()
                lastErrors.addLast(ToolErrorRecord(tool = name, message = errorMessage, at = System.currentTimeMillis()))
            }
        }

        private fun metricsAsJson(): JsonArray {
            val arr = JsonArray()
            stats.toSortedMap().forEach { (name, s) ->
                val avg = if (s.calls == 0) 0.0 else s.totalMs.toDouble() / s.calls.toDouble()
                arr.add(JsonObject().apply {
                    addProperty("tool", name)
                    addProperty("calls", s.calls)
                    addProperty("errors", s.errors)
                    addProperty("avg_ms", avg)
                    addProperty("total_ms", s.totalMs)
                })
            }
            return arr
        }

        fun snapshotMetricsUi(): List<ToolMetricUi> {
            return stats.toSortedMap().map { (name, s) ->
                val avg = if (s.calls == 0) 0.0 else s.totalMs.toDouble() / s.calls.toDouble()
                ToolMetricUi(tool = name, calls = s.calls, errors = s.errors, avgMs = avg, totalMs = s.totalMs)
            }
        }

        private fun lastToolErrorsAsJson(limit: Int): JsonArray {
            val arr = JsonArray()
            val items = lastErrors.toList().takeLast(limit)
            items.forEach { rec ->
                arr.add(JsonObject().apply {
                    addProperty("tool", rec.tool)
                    addProperty("message", rec.message)
                    addProperty("at", rec.at)
                })
            }
            return arr
        }

        fun snapshotErrorsUi(limit: Int): List<ToolErrorUi> {
            return lastErrors.toList().takeLast(limit).map { ToolErrorUi(tool = it.tool, message = it.message, at = it.at) }
        }

        fun clearStatsAndErrors() {
            stats.clear()
            lastErrors.clear()
        }

        fun listRegisteredToolNames(): Set<String> = handlers.keys.toSet()

        private fun settingsSummaryAsJson(): JsonObject {
            return JsonObject().apply {
                addProperty("base_url", settings.baseUrl)
                addProperty("selected_model", settings.selectedModel)
                addProperty("has_api_key", settings.apiKey.isNotBlank())
                addProperty("has_exa_key", settings.exaApiKey.isNotBlank())
                addProperty("has_tavily_key", settings.tavilyApiKey.isNotBlank())
                addProperty("mcp_servers_count", settings.mcpServers.orEmpty().size)
                addProperty("memories_count", settings.memoriesV2.orEmpty().size)
                addProperty("folders_count", settings.folders.orEmpty().size)
                addProperty("models_count", settings.availableModels.orEmpty().size)
            }
        }
    }

    private val toolEngine = ToolEngine()

    fun listRegisteredToolNames(): Set<String> = toolEngine.listRegisteredToolNames()

    suspend fun runTool(name: String, args: JsonObject, sIdx: Int = 0, aiMsgIndex: Int = 0): String {
        return toolEngine.execute(name, args, sIdx, aiMsgIndex)
    }

    init {
        viewModelScope.launch {
            val loadedActions = withContext(Dispatchers.IO) { actionsStore.load() }
            val loadedReminders = withContext(Dispatchers.IO) { remindersStore.load() }
            withContext(Dispatchers.Main) {
                recentActions.clear()
                recentActions.addAll(loadedActions)
                reminders.clear()
                reminders.addAll(loadedReminders)
            }
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                loadedReminders.filter { it.atMs > now }.forEach { r ->
                    reminderScheduler.schedule(r.id, r.atMs, r.title, r.text)
                }
            }
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
        val legacyFromV2 = normalized.memoriesV2.orEmpty().map { entry ->
            val k = entry.key?.trim().orEmpty()
            if (k.isNotBlank()) "$k: ${entry.value}" else entry.value
        }
        val finalSettings = normalized.copy(memories = legacyFromV2)
        settings = finalSettings
        settingsStore.save(finalSettings)
    }

    fun updateSettings(newSettings: AppSettings) { saveSettings(newSettings) }

    fun getToolMetricsUi(): List<ToolMetricUi> = toolEngine.snapshotMetricsUi()
    fun getToolErrorsUi(limit: Int = 20): List<ToolErrorUi> = toolEngine.snapshotErrorsUi(limit.coerceIn(1, TOOL_ERROR_BUFFER_SIZE))
    fun clearToolDiagnostics() = toolEngine.clearStatsAndErrors()

    fun getRecentActionsUi(limit: Int = 50): List<RecentActionEntry> {
        val l = limit.coerceIn(1, 200)
        return recentActions.takeLast(l).asReversed()
    }

    fun clearRecentActions() {
        recentActions.clear()
        actionsStore.save(emptyList())
    }

    private fun appendRecentAction(entry: RecentActionEntry) {
        val next = (recentActions + entry).takeLast(200)
        recentActions.clear()
        recentActions.addAll(next)
        viewModelScope.launch(Dispatchers.IO) { actionsStore.save(next) }
    }

    fun listScheduledRemindersUi(limit: Int = 100): List<ReminderEntry> {
        val l = limit.coerceIn(1, 500)
        return reminders.sortedBy { it.atMs }.take(l)
    }

    fun cancelScheduledReminder(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        cancelReminderInternal(normalized)
    }

    fun clearAllScheduledReminders() {
        val snapshot = reminders.toList()
        snapshot.forEach { r -> reminderScheduler.cancel(r.id, r.title, r.text) }
        saveReminders(emptyList())
    }

    private fun saveReminders(next: List<ReminderEntry>) {
        reminders.clear()
        reminders.addAll(next)
        remindersStore.save(next)
    }

    private fun scheduleReminderInternal(entry: ReminderEntry) {
        val next = (reminders + entry).distinctBy { it.id }.sortedBy { it.atMs }.takeLast(500)
        saveReminders(next)
        reminderScheduler.schedule(entry.id, entry.atMs, entry.title, entry.text)
    }

    private fun cancelReminderInternal(id: String) {
        val existing = reminders.firstOrNull { it.id == id }
        val next = reminders.filterNot { it.id == id }
        saveReminders(next)
        reminderScheduler.cancel(id, existing?.title, existing?.text)
    }

    private fun getCalendarCandidateIds(requireSync: Boolean): List<Long> {
        if (!hasCalendarPermissions()) return emptyList()
        val cr = getApplication<Application>().contentResolver
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = buildString {
            append("${CalendarContract.Calendars.VISIBLE}=1")
            if (requireSync) append(" AND ${CalendarContract.Calendars.SYNC_EVENTS}=1")
        }
        val order = "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        val ids = mutableListOf<Long>()
        cr.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, order)?.use { c ->
            val idxId = c.getColumnIndex(CalendarContract.Calendars._ID)
            while (c.moveToNext()) {
                if (idxId >= 0) ids.add(c.getLong(idxId))
            }
        }
        return ids
    }

    private fun insertCalendarEventSilent(
        title: String,
        startMs: Long,
        endMs: Long,
        location: String?,
        notes: String?
    ): Pair<String, Long>? {
        val cr = getApplication<Application>().contentResolver
        val candidates = getCalendarCandidateIds(requireSync = true).ifEmpty { getCalendarCandidateIds(requireSync = false) }
        if (candidates.isEmpty()) return null
        for (calId in candidates) {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
                if (!notes.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, notes)
            }
            val uri = try {
                cr.insert(CalendarContract.Events.CONTENT_URI, values)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
            val eventId = uri?.lastPathSegment
            if (!eventId.isNullOrBlank()) return eventId to calId
        }
        return null
    }

    private fun listCalendarEvents(startMs: Long, endMs: Long, limit: Int): JsonArray {
        val arr = JsonArray()
        if (!hasCalendarPermissions()) return arr
        val cr = getApplication<Application>().contentResolver
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)
        val uri = builder.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION
        )
        val order = "${CalendarContract.Instances.BEGIN} ASC"
        cr.query(uri, projection, null, null, order)?.use { c ->
            var count = 0
            while (c.moveToNext() && count < limit) {
                val eventId = c.getLong(0).toString()
                val title = c.getString(1).orEmpty()
                val begin = c.getLong(2)
                val end = c.getLong(3)
                val allDay = c.getInt(4) == 1
                val loc = c.getString(5)
                arr.add(JsonObject().apply {
                    addProperty("event_id", eventId)
                    addProperty("title", title)
                    addProperty("start_ms", begin)
                    addProperty("end_ms", end)
                    addProperty("all_day", allDay)
                    if (!loc.isNullOrBlank()) addProperty("location", loc)
                })
                count += 1
            }
        }
        return arr
    }

    private fun deleteCalendarEvent(eventId: String): Boolean {
        if (!hasCalendarPermissions()) return false
        val cr = getApplication<Application>().contentResolver
        val where = "${CalendarContract.Events._ID}=?"
        val rows = cr.delete(CalendarContract.Events.CONTENT_URI, where, arrayOf(eventId))
        return rows > 0
    }

    private suspend fun requestUserApproval(tool: String, title: String, detail: String? = null, timeoutMs: Long = 60_000L): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            pendingApprovalDeferred?.cancel()
            pendingApprovalDeferred = deferred
            pendingUserApproval = PendingUserApproval(tool = tool, title = title, detail = detail)
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            false
        } finally {
            withContext(Dispatchers.Main) {
                pendingUserApproval = null
                pendingApprovalDeferred = null
            }
        }
    }

    fun respondToPendingUserApproval(approved: Boolean) {
        pendingApprovalDeferred?.complete(approved)
    }

    private suspend fun requestCreateJsonDocument(suggestedName: String, timeoutMs: Long = 120_000L): Uri? {
        val deferred = CompletableDeferred<Uri?>()
        withContext(Dispatchers.Main) {
            pendingDocumentDeferred?.cancel()
            pendingDocumentDeferred = deferred
            pendingDocumentRequest = PendingDocumentRequest(mode = "create_json", suggestedName = suggestedName)
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            withContext(Dispatchers.Main) {
                pendingDocumentRequest = null
                pendingDocumentDeferred = null
            }
        }
    }

    private suspend fun requestOpenDocument(mimeTypes: List<String>, timeoutMs: Long = 120_000L): Uri? {
        val deferred = CompletableDeferred<Uri?>()
        withContext(Dispatchers.Main) {
            pendingDocumentDeferred?.cancel()
            pendingDocumentDeferred = deferred
            pendingDocumentRequest = PendingDocumentRequest(mode = "open", mimeTypes = mimeTypes)
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            withContext(Dispatchers.Main) {
                pendingDocumentRequest = null
                pendingDocumentDeferred = null
            }
        }
    }

    fun onDocumentPicked(uri: Uri?) {
        pendingDocumentDeferred?.complete(uri)
    }

    fun addMemory(fact: String) {
        val normalized = fact.trim().replace("\\s+".toRegex(), " ")
        if (normalized.isBlank()) return
        val capped = normalized.take(MAX_MEMORY_CHARS)
        val existing = settings.memoriesV2.orEmpty().asSequence()
            .filter { it.key.isNullOrBlank() }
            .map { it.value.trim().replace("\\s+".toRegex(), " ") }
            .toSet()
        if (capped in existing) return
        val now = System.currentTimeMillis()
        val next = (settings.memoriesV2.orEmpty() + MemoryEntry(value = capped, createdAt = now, updatedAt = now)).takeLast(MAX_MEMORIES)
        saveSettings(settings.copy(memoriesV2 = next))
    }
    fun deleteMemory(index: Int) {
        val updated = settings.memoriesV2.orEmpty().toMutableList().apply { if (index in indices) removeAt(index) }
        saveSettings(settings.copy(memoriesV2 = updated))
    }
    fun updateMemory(index: Int, text: String) {
        val normalized = text.trim().replace("\\s+".toRegex(), " ")
        if (normalized.isBlank()) return
        val capped = normalized.take(MAX_MEMORY_CHARS)
        val now = System.currentTimeMillis()
        val updated = settings.memoriesV2.orEmpty().toMutableList().apply {
            if (index in indices) {
                val existing = this[index]
                this[index] = existing.copy(value = capped, updatedAt = now)
            }
        }
        saveSettings(settings.copy(memoriesV2 = updated))
    }

    private fun listMemoryKeysAsJson(): JsonArray {
        val keys = settings.memoriesV2.orEmpty()
            .mapNotNull { it.key?.trim()?.takeIf { k -> k.isNotBlank() } }
            .map { it.lowercase(Locale.ROOT) }
            .distinct()
            .sorted()
        val arr = JsonArray()
        keys.forEach { arr.add(it) }
        return arr
    }

    private fun renameMemoryKey(oldKey: String, newKey: String) {
        val from = oldKey.trim()
        val to = newKey.trim()
        if (from.isBlank() || to.isBlank()) return
        val list = settings.memoriesV2.orEmpty().toMutableList()
        val fromIdx = list.indexOfFirst { it.key?.equals(from, ignoreCase = true) == true }
        if (fromIdx < 0) return
        val toIdx = list.indexOfFirst { it.key?.equals(to, ignoreCase = true) == true }
        val now = System.currentTimeMillis()
        if (toIdx >= 0 && toIdx != fromIdx) {
            val merged = list[toIdx].copy(value = list[fromIdx].value, updatedAt = now)
            val max = maxOf(fromIdx, toIdx)
            val min = minOf(fromIdx, toIdx)
            list[max] = merged
            list.removeAt(min)
        } else {
            val cur = list[fromIdx]
            list[fromIdx] = cur.copy(key = to.take(60), updatedAt = now)
        }
        saveSettings(settings.copy(memoriesV2 = list.takeLast(MAX_MEMORIES)))
    }

    private fun mergeMemories() {
        val list = settings.memoriesV2.orEmpty()
        if (list.isEmpty()) return
        val byKey = LinkedHashMap<String, MemoryEntry>()
        val unkeyed = LinkedHashMap<String, MemoryEntry>()
        list.forEach { entry ->
            val k = entry.key?.trim().orEmpty()
            if (k.isNotBlank()) {
                val lk = k.lowercase(Locale.ROOT)
                val existing = byKey[lk]
                if (existing == null || entry.updatedAt >= existing.updatedAt) {
                    byKey[lk] = entry.copy(key = k.take(60))
                }
            } else {
                val v = entry.value.trim()
                val existing = unkeyed[v]
                if (existing == null || entry.updatedAt >= existing.updatedAt) {
                    unkeyed[v] = entry.copy(value = v.take(MAX_MEMORY_CHARS))
                }
            }
        }
        val merged = (byKey.values + unkeyed.values).sortedBy { it.updatedAt }.takeLast(MAX_MEMORIES)
        saveSettings(settings.copy(memoriesV2 = merged))
    }

    private fun memoryGc(maxEntries: Int?, keepRecentDays: Int?) {
        val list = settings.memoriesV2.orEmpty()
        if (list.isEmpty()) return
        val maxE = (maxEntries ?: MAX_MEMORIES).coerceIn(10, 400)
        val cutoff = keepRecentDays?.takeIf { it > 0 }?.let { days ->
            System.currentTimeMillis() - days.toLong() * 24L * 60L * 60L * 1000L
        }
        val kept = if (cutoff != null) {
            val recent = list.filter { it.updatedAt >= cutoff }
            val rest = list.filter { it.updatedAt < cutoff }
            (rest.sortedBy { it.updatedAt } + recent.sortedBy { it.updatedAt }).takeLast(maxE)
        } else {
            list.sortedBy { it.updatedAt }.takeLast(maxE)
        }
        saveSettings(settings.copy(memoriesV2 = kept))
    }

    private fun loadNotes(): MutableList<NoteEntry> = notesStore.load().toMutableList()

    private fun saveNote(title: String, content: String): NoteEntry {
        val now = System.currentTimeMillis()
        val entry = NoteEntry(title = title.trim().take(80), content = content.trim(), createdAt = now, updatedAt = now)
        val list = loadNotes()
        list.add(entry)
        notesStore.save(list)
        return entry
    }

    private fun deleteNote(id: String): Boolean {
        val list = loadNotes()
        val before = list.size
        list.removeAll { it.id == id }
        if (list.size == before) return false
        notesStore.save(list)
        return true
    }

    private fun listNotesAsJson(limit: Int): JsonArray {
        val notes = notesStore.load().sortedByDescending { it.updatedAt }.take(limit)
        val arr = JsonArray()
        notes.forEach { n ->
            arr.add(JsonObject().apply {
                addProperty("id", n.id)
                addProperty("title", n.title)
                addProperty("updated_at", n.updatedAt)
                addProperty("content_preview", n.content.take(200))
            })
        }
        return arr
    }

    private fun searchNotesAsJson(query: String, limit: Int): JsonArray {
        val q = query.trim().lowercase(Locale.ROOT)
        val notes = notesStore.load()
            .filter { (it.title + "\n" + it.content).lowercase(Locale.ROOT).contains(q) }
            .sortedByDescending { it.updatedAt }
            .take(limit)
        val arr = JsonArray()
        notes.forEach { n ->
            arr.add(JsonObject().apply {
                addProperty("id", n.id)
                addProperty("title", n.title)
                addProperty("updated_at", n.updatedAt)
                addProperty("content_preview", n.content.take(200))
            })
        }
        return arr
    }

    private fun addDoc(title: String, content: String, tags: List<String>): DocEntry {
        return docsStore.add(title = title.trim().take(80), content = content.trim(), tags = tags)
    }

    private fun listDocsAsJson(limit: Int): JsonArray {
        val docs = docsStore.list(limit)
        val arr = JsonArray()
        docs.forEach { d ->
            arr.add(JsonObject().apply {
                addProperty("id", d.id)
                addProperty("title", d.title)
                addProperty("tags", d.tags.joinToString(","))
                addProperty("updated_at", d.updatedAt)
                addProperty("content_preview", d.content.take(240))
            })
        }
        return arr
    }

    private fun searchDocsAsJson(query: String, limit: Int): JsonArray {
        val docs = docsStore.search(query, limit)
        val arr = JsonArray()
        docs.forEach { d ->
            val idx = d.content.lowercase(Locale.ROOT).indexOf(query.trim().lowercase(Locale.ROOT))
            val snippet = if (idx >= 0) {
                val start = (idx - 80).coerceAtLeast(0)
                val end = (idx + 160).coerceAtMost(d.content.length)
                d.content.substring(start, end)
            } else d.content.take(240)
            arr.add(JsonObject().apply {
                addProperty("id", d.id)
                addProperty("title", d.title)
                addProperty("tags", d.tags.joinToString(","))
                addProperty("updated_at", d.updatedAt)
                addProperty("snippet", snippet)
            })
        }
        return arr
    }

    private fun copyToClipboard(text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MuMuChat", text))
    }

    private fun shareText(text: String, title: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(Intent.createChooser(intent, title ?: "分享"))
    }

    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
        return cleaned.take(60).ifBlank { "session" }
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
            val selectedFiles = withContext(Dispatchers.Main) { selectedFileUris.toList() }
            val imagePaths = selected.mapNotNull { saveImageToInternalStorage(context, it) }
            val finalImageUrls = imagePaths.map { "file://$it" }
            val cachedFiles = selectedFiles.mapNotNull { cacheUriToCacheFile(context, it) }
            val imagesFromFiles = mutableListOf<String>()
            val baseAttachments = mutableListOf<ChatAttachment>()
            cachedFiles.forEach { cached ->
                val meta = cached.meta
                val file = cached.file
                val mime = meta.mimeType
                if (!mime.isNullOrBlank() && mime.startsWith("image/")) {
                    val uri = file?.let { Uri.fromFile(it) } ?: Uri.parse(meta.sourceUri)
                    val path = saveImageToInternalStorage(context, uri)
                    if (path != null) {
                        imagesFromFiles.add("file://$path")
                    } else {
                        baseAttachments.add(
                            ChatAttachment(
                                displayName = meta.displayName,
                                mimeType = mime,
                                sizeBytes = meta.sizeBytes,
                                localPath = file?.absolutePath,
                                sourceUri = meta.sourceUri,
                                kind = AttachmentKind.IMAGE
                            )
                        )
                    }
                } else {
                    baseAttachments.add(
                        ChatAttachment(
                            displayName = meta.displayName,
                            mimeType = mime,
                            sizeBytes = meta.sizeBytes,
                            localPath = file?.absolutePath,
                            sourceUri = meta.sourceUri,
                            kind = classifyAttachmentKind(meta.displayName, mime)
                        )
                    )
                }
            }

            val prepared = prepareAttachmentsForUserTurn(context, baseAttachments)
            val combinedImageUrls = finalImageUrls + imagesFromFiles + prepared.extraImageUrls
            val currentInput = buildString {
                append(text)
                if (prepared.injectedText.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(prepared.injectedText.trim())
                }
            }

            withContext(Dispatchers.Main) {
                val userMsg = ChatMessage(
                    content = currentInput,
                    role = MessageRole.USER,
                    imageUrl = combinedImageUrls.firstOrNull(),
                    imageUrls = combinedImageUrls,
                    attachments = prepared.attachments
                )
                val aiMsgPlaceholder = ChatMessage(content = "", role = MessageRole.ASSISTANT, steps = emptyList())

                val existing = sessions[sIdx].messages.orEmpty()
                val isFirst = existing.isEmpty()
                val combined = existing + userMsg + aiMsgPlaceholder
                sessions[sIdx] = sessions[sIdx].copy(messages = combined)
                val aiMsgIndex = combined.size - 1

                selectedImageUris.clear()
                selectedFileUris.clear()
                executeMultiStepTurn(sIdx, aiMsgIndex, mutableListOf())
                if (isFirst) autoRenameSession(sIdx, text)
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
            val tz = TimeZone.getTimeZone("Asia/Shanghai")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA).apply { timeZone = tz }
            val now = Date()
            val currentTime = "${sdf.format(now)} (Asia/Shanghai, epoch_ms=${now.time})"
            val systemPrompt = """$CORE_SYSTEM_PROMPT
当前系统时间: $currentTime

用户个性化设定:
${settings.userPersona}
            """.trimIndent().let { base ->
                val memories = settings.memoriesV2.orEmpty().takeLast(40).map { entry ->
                    val k = entry.key?.trim().orEmpty()
                    if (k.isNotBlank()) "$k: ${entry.value}" else entry.value
                }
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

            toolEngine.execute(name, args, sIdx, aiMsgIndex)
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

    private fun validateBrowseUrl(url: String): BrowseSafety.Result {
        return BrowseSafety.validate(
            url = url,
            allowlist = settings.browseAllowlist.orEmpty(),
            denylist = settings.browseDenylist.orEmpty(),
            resolver = object : BrowseSafety.HostResolver {
                override fun resolveAll(host: String) = InetAddress.getAllByName(host).toList()
            }
        )
    }

    private fun browsePolicyAsJson(): JsonObject {
        val allow = JsonArray().apply { settings.browseAllowlist.orEmpty().forEach { add(it) } }
        val deny = JsonArray().apply { settings.browseDenylist.orEmpty().forEach { add(it) } }
        return JsonObject().apply {
            add("allowlist", allow)
            add("denylist", deny)
        }
    }

    private fun setBrowsePolicy(allowlist: JsonArray?, denylist: JsonArray?) {
        val allow = allowlist?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?.map { it.trim().trimStart('.').lowercase(Locale.ROOT) }
            ?.filter { it.isNotBlank() }
            ?.distinct()
        val deny = denylist?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?.map { it.trim().trimStart('.').lowercase(Locale.ROOT) }
            ?.filter { it.isNotBlank() }
            ?.distinct()
        val next = settings.copy(
            browseAllowlist = allow ?: settings.browseAllowlist.orEmpty(),
            browseDenylist = deny ?: settings.browseDenylist.orEmpty()
        )
        saveSettings(next)
    }

    private fun networkStatusAsJson(): JsonObject {
        val baseUrl = settings.baseUrl.trim()
        val targets = listOf(
            baseUrl,
            "https://api.exa.ai/",
            "https://60s.viki.moe/"
        ).distinct()
        val arr = JsonArray()
        targets.forEach { url ->
            arr.add(checkReachability(url))
        }
        return JsonObject().apply { add("targets", arr) }
    }

    private fun checkReachability(url: String): JsonObject {
        val start = SystemClock.elapsedRealtime()
        val httpUrl = url.toHttpUrlOrNull()
        if (httpUrl == null) {
            return JsonObject().apply {
                addProperty("url", url)
                addProperty("ok", false)
                addProperty("error", "invalid_url")
            }
        }
        val hostOk = try {
            InetAddress.getByName(httpUrl.host)
            true
        } catch (_: Exception) {
            false
        }
        if (!hostOk) {
            return JsonObject().apply {
                addProperty("url", url)
                addProperty("ok", false)
                addProperty("error", "dns_failed")
            }
        }
        val probeClient = client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .build()
        return try {
            val req = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", "MuMuChat/2.1")
                .get()
                .build()
            probeClient.newCall(req).execute().use { resp ->
                JsonObject().apply {
                    addProperty("url", url)
                    addProperty("ok", true)
                    addProperty("http_code", resp.code)
                    addProperty("latency_ms", (SystemClock.elapsedRealtime() - start).toLong())
                }
            }
        } catch (_: Exception) {
            JsonObject().apply {
                addProperty("url", url)
                addProperty("ok", false)
                addProperty("error", "connect_failed")
                addProperty("latency_ms", (SystemClock.elapsedRealtime() - start).toLong())
            }
        }
    }

    private fun summarizeSessionLocalAsJson(sIdx: Int, limit: Int): JsonObject {
        val session = sessions.getOrNull(sIdx)
        if (session == null) return JsonObject().apply { addProperty("error", "invalid_session") }
        val msgs = session.messages.orEmpty().takeLast(limit)
        val lastUser = msgs.lastOrNull { it.role == MessageRole.USER }?.content?.trim().orEmpty()
        val lastAssistant = msgs.lastOrNull { it.role == MessageRole.ASSISTANT }?.content?.trim().orEmpty()
        val recentUser = msgs.filter { it.role == MessageRole.USER }.takeLast(5).map { it.content.trim().take(400) }
        val recentArr = JsonArray().apply { recentUser.forEach { add(it) } }
        return JsonObject().apply {
            addProperty("session_id", session.id)
            addProperty("title", session.title)
            addProperty("messages_count", msgs.size)
            addProperty("last_user", lastUser.take(800))
            addProperty("last_assistant", lastAssistant.take(800))
            add("recent_user_messages", recentArr)
        }
    }

    private fun extractTodosFromChatAsJson(sIdx: Int, limit: Int): JsonObject {
        val session = sessions.getOrNull(sIdx)
        if (session == null) return JsonObject().apply { addProperty("error", "invalid_session") }
        val msgs = session.messages.orEmpty().takeLast(limit)
        val items = JsonArray()
        val seen = mutableSetOf<String>()
        val bullet = Regex("""^\s*(?:-|\*|•|\d+[\.\)])\s+(.+)$""")
        val todoHint = Regex("""(?i)\bTODO\b|待办|需要|请帮我|麻烦|想要""")
        for (i in msgs.indices.reversed()) {
            val msg = msgs[i]
            if (msg.role != MessageRole.USER) continue
            val lines = msg.content.split('\n')
            lines.forEach { line ->
                val raw = line.trim()
                if (raw.isBlank()) return@forEach
                val m = bullet.find(raw)
                val candidate = (m?.groupValues?.getOrNull(1) ?: raw)
                    .trim()
                    .trimEnd('。', '.', '！', '!')
                    .take(200)
                if (candidate.isBlank()) return@forEach
                if (!todoHint.containsMatchIn(raw) && m == null) return@forEach
                if (!seen.add(candidate.lowercase(Locale.ROOT))) return@forEach
                items.add(JsonObject().apply {
                    addProperty("text", candidate)
                    addProperty("message_index", i)
                })
            }
            if (items.size() >= 30) break
        }
        return JsonObject().apply {
            addProperty("session_id", session.id)
            addProperty("count", items.size())
            add("items", items)
        }
    }

    private fun fetchHtmlWithRedirects(startUrl: String): Pair<String, String> {
        val result = RedirectFollower.follow(
            startUrl = startUrl,
            maxRedirects = MAX_BROWSE_REDIRECTS,
            fetch = { url ->
                val v = validateBrowseUrl(url)
                if (!v.ok) throw ToolException(v.code ?: "ssrf_blocked", v.message ?: "浏览被阻止")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android; MuMuChat/2.1)")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()
                browseClient.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        RedirectFollower.FetchResult(
                            statusCode = response.code,
                            location = response.header("Location")
                        )
                    } else {
                        val source = response.body?.source()
                        if (source == null) {
                            RedirectFollower.FetchResult(statusCode = response.code, body = "")
                        } else {
                            val buffer = Buffer()
                            while (buffer.size < MAX_BROWSE_BYTES) {
                                val toRead = minOf(8192L, MAX_BROWSE_BYTES - buffer.size)
                                val read = source.read(buffer, toRead)
                                if (read == -1L) break
                            }
                            RedirectFollower.FetchResult(statusCode = response.code, body = buffer.readUtf8())
                        }
                    }
                }
            },
            resolve = { base, location ->
                val baseUrl = base.toHttpUrlOrNull() ?: return@follow null
                baseUrl.resolve(location)?.toString()
            }
        )
        if (!result.ok) throw ToolException(result.code ?: "browse_failed", result.message ?: "浏览失败")
        return result.finalUrl.orEmpty() to result.body.orEmpty()
    }

    private data class RedirectUrlResult(
        val ok: Boolean,
        val finalUrl: String? = null,
        val code: String? = null,
        val message: String? = null
    )

    private fun resolveRedirectFinalUrl(startUrl: String): RedirectUrlResult {
        val result = RedirectFollower.follow(
            startUrl = startUrl,
            maxRedirects = 5,
            fetch = { url ->
                val v = BrowseSafety.validate(url, allowlist = emptyList(), denylist = emptyList())
                if (!v.ok) throw ToolException(v.code ?: "ssrf_blocked", v.message ?: "浏览被阻止")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "MuMuChat/2.1")
                    .header("Range", "bytes=0-0")
                    .get()
                    .build()
                redirectClient.newCall(request).execute().use { response ->
                    RedirectFollower.FetchResult(
                        statusCode = response.code,
                        location = response.header("Location")
                    )
                }
            },
            resolve = { base, location ->
                val baseUrl = base.toHttpUrlOrNull() ?: return@follow null
                baseUrl.resolve(location)?.toString()
            }
        )
        return if (!result.ok) {
            RedirectUrlResult(ok = false, code = result.code ?: "redirect_failed", message = result.message ?: "重定向失败")
        } else {
            RedirectUrlResult(ok = true, finalUrl = result.finalUrl ?: startUrl)
        }
    }

    private fun fetchPublicEndpointDynamic(
        groupId: String,
        url: String,
        ttlMs: Long = 5 * 60_000L,
        timeoutMs: Long = 6_000L,
        maxBytes: Long = 512 * 1024L,
        maxPerMinute: Int = 60
    ): RemoteFetchResult {
        val ep = RemoteEndpoint(
            id = groupId,
            url = url,
            ttlMs = ttlMs,
            timeoutMs = timeoutMs,
            maxBytes = maxBytes,
            maxPerMinute = maxPerMinute,
            followRedirects = false
        )
        val cacheKey = "$groupId|${url.trim()}"
        return remoteApiClient.fetch(ep, bypassCache = false, cacheKey = cacheKey)
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
        settings.memoriesV2.orEmpty().forEachIndexed { idx, entry ->
            arr.add(JsonObject().apply {
                addProperty("index", idx)
                addProperty("id", entry.id)
                addProperty("key", entry.key)
                addProperty("value", entry.value)
                addProperty("updated_at", entry.updatedAt)
            })
        }
        return arr
    }

    private fun searchMemoriesAsJson(query: String): JsonArray {
        val q = query.trim().lowercase(Locale.ROOT)
        val arr = JsonArray()
        settings.memoriesV2.orEmpty().forEachIndexed { idx, entry ->
            val hay = ((entry.key ?: "") + " " + entry.value).lowercase(Locale.ROOT)
            if (hay.contains(q)) {
                arr.add(JsonObject().apply {
                    addProperty("index", idx)
                    addProperty("id", entry.id)
                    addProperty("key", entry.key)
                    addProperty("value", entry.value)
                    addProperty("updated_at", entry.updatedAt)
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

    private fun searchSessionsAsJson(query: String, limit: Int): JsonArray {
        val q = query.trim().lowercase(Locale.ROOT)
        val results = JsonArray()
        if (q.isBlank()) return results
        var added = 0
        for (s in sessions) {
            if (added >= limit) break
            val messages = s.messages.orEmpty()
            for (i in messages.indices.reversed()) {
                val msg = messages[i]
                val content = msg.content
                if (content.lowercase(Locale.ROOT).contains(q)) {
                    results.add(JsonObject().apply {
                        addProperty("session_id", s.id)
                        addProperty("title", s.title)
                        addProperty("folder", s.folder)
                        addProperty("message_index", i)
                        addProperty("role", msg.role.name.lowercase(Locale.ROOT))
                        addProperty("snippet", content.trim().replace("\n", " ").take(220))
                    })
                    added += 1
                    break
                }
            }
        }
        return results
    }

    private fun upsertMemory(key: String, value: String) {
        val k = key.trim().replace("\\s+".toRegex(), " ").take(60)
        val v = value.trim().replace("\\s+".toRegex(), " ").take(MAX_MEMORY_CHARS)
        if (k.isBlank() || v.isBlank()) return
        val now = System.currentTimeMillis()
        val updated = settings.memoriesV2.orEmpty().toMutableList()
        val idx = updated.indexOfFirst { it.key?.equals(k, ignoreCase = true) == true }
        if (idx >= 0) {
            val existing = updated[idx]
            updated[idx] = existing.copy(key = k, value = v, updatedAt = now)
        } else {
            updated.add(MemoryEntry(key = k, value = v, createdAt = now, updatedAt = now))
        }
        saveSettings(settings.copy(memoriesV2 = updated.takeLast(MAX_MEMORIES)))
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

    private fun executeVisionOcrUrlSync(imageUrl: String, langHint: String?, modelOverride: String?): String = try {
        val model = modelOverride?.takeIf { it.isNotBlank() } ?: settings.selectedModel
        val system = "你是 OCR 引擎。只输出识别出的文字内容，不要解释，不要添加多余符号。"
        val userText = buildString {
            append("请对图片进行 OCR，提取其中的文字。")
            if (!langHint.isNullOrBlank()) append("\n语言提示：").append(langHint.trim())
        }

        val messages = JsonArray().apply {
            add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
            add(JsonObject().apply {
                addProperty("role", "user")
                add("content", JsonArray().apply {
                    add(JsonObject().apply { addProperty("type", "text"); addProperty("text", userText) })
                    add(JsonObject().apply {
                        addProperty("type", "image_url")
                        add("image_url", JsonObject().apply { addProperty("url", imageUrl) })
                    })
                })
            })
        }

        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            addProperty("stream", false)
            addProperty("max_tokens", 1200)
        }

        val request = Request.Builder()
            .url("${settings.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty().take(600)
                return@use toolErr("vision_ocr_url", "OCR 失败: HTTP ${response.code} ${errBody}".trim(), code = response.code.toString())
            }
            val bodyString = response.body?.string()
            if (bodyString.isNullOrBlank()) return@use toolErr("vision_ocr_url", "OCR 失败: 空响应")
            val jsonObject = try { gson.fromJson(bodyString, JsonObject::class.java) } catch (_: Exception) { null }
                ?: return@use toolErr("vision_ocr_url", "OCR 失败: 响应解析错误")
            val text = jsonObject
                .getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?.trim()
                .orEmpty()
            if (text.isBlank()) return@use toolErr("vision_ocr_url", "OCR 失败: 无内容", code = "empty")
            toolOk("vision_ocr_url", JsonObject().apply {
                addProperty("model", model)
                addProperty("image_url", imageUrl)
                addProperty("text", text)
            })
        }
    } catch (e: Exception) {
        toolErr("vision_ocr_url", "OCR 失败: ${e.message}")
    }

    private fun executeAudioTranscribeSync(uri: Uri, language: String?, prompt: String?): String {
        return try {
        startKeepAlive("audio", "音频转写中", uri.toString().take(120))
        val cr = getApplication<Application>().contentResolver
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: run {
            stopKeepAlive("audio")
            return toolErr("audio_transcribe", "读取音频失败", code = "read_failed")
        }
        if (bytes.isEmpty()) {
            stopKeepAlive("audio")
            return toolErr("audio_transcribe", "音频为空", code = "empty")
        }
        if (bytes.size > 25 * 1024 * 1024) {
            stopKeepAlive("audio")
            return toolErr("audio_transcribe", "音频过大（>25MB）", code = "too_large")
        }

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("model", "whisper-1")
            if (!language.isNullOrBlank()) addFormDataPart("language", language.trim())
            if (!prompt.isNullOrBlank()) addFormDataPart("prompt", prompt.trim())
            addFormDataPart("file", "audio", bytes.toRequestBody("application/octet-stream".toMediaType()))
        }.build()

        val request = Request.Builder()
            .url("${settings.baseUrl}/audio/transcriptions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(multipart)
            .build()

        client.newCall(request).execute().use { response ->
            stopKeepAlive("audio")
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty().take(600)
                return@use toolErr("audio_transcribe", "转写失败: HTTP ${response.code} ${errBody}".trim(), code = response.code.toString())
            }
            val bodyString = response.body?.string()
            if (bodyString.isNullOrBlank()) return@use toolErr("audio_transcribe", "转写失败: 空响应")
            val jsonObject = try { gson.fromJson(bodyString, JsonObject::class.java) } catch (_: Exception) { null }
                ?: return@use toolErr("audio_transcribe", "转写失败: 响应解析错误")
            val text = jsonObject.get("text")?.asString?.trim().orEmpty()
            if (text.isBlank()) return@use toolErr("audio_transcribe", "转写失败: 无 text 字段", code = "empty")
            toolOk("audio_transcribe", JsonObject().apply {
                addProperty("text", text)
                addProperty("bytes", bytes.size)
            })
        }
        } catch (e: Exception) {
            stopKeepAlive("audio")
            toolErr("audio_transcribe", "转写失败: ${e.message}")
        }
    }

    private fun executeBrowseUrlSync(url: String): String = try {
        startKeepAlive("browse", "精读进行中", url.trim().take(120))
        val v = validateBrowseUrl(url)
        if (!v.ok) toolErr("browse_url", v.message ?: "浏览被阻止", v.code) else run {
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
    } catch (e: ToolException) {
        Log.e(TAG, "网页浏览阻止: $url", e)
        toolErr("browse_url", e.message, e.code)
    } catch (e: Exception) {
        Log.e(TAG, "网页浏览异常: $url", e)
        toolErr("browse_url", "浏览失败: ${e.message}", code = "browse_failed")
    } finally {
        stopKeepAlive("browse")
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
            val t = TextTruncate.limit(output, JS_MAX_OUTPUT_CHARS)

            toolOk("calculate", JsonObject().apply {
                addProperty("output", t.text.ifBlank { "" })
                addProperty("output_length", t.length)
                addProperty("truncated", t.truncated)
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

    private fun executeTavilySearchSync(query: String, maxResults: Int, searchDepth: String): String = try {
        val requestBody = JsonObject().apply {
            addProperty("api_key", settings.tavilyApiKey)
            addProperty("query", query)
            addProperty("max_results", maxResults)
            addProperty("search_depth", searchDepth)
            addProperty("include_answer", false)
            addProperty("include_raw_content", false)
            addProperty("include_images", false)
        }

        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Content-Type", "application/json")
            .header("User-Agent", "MuMuChat/2.1")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        mcpHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val msg = when (response.code) {
                    401 -> "API Key 无效"
                    429 -> "请求过于频繁，请稍后重试"
                    else -> "HTTP ${response.code}"
                }
                return@use toolErr("tavily_search", "搜索失败: $msg", code = response.code.toString())
            }
            val bodyString = response.body?.string()
            if (bodyString.isNullOrBlank()) return@use toolErr("tavily_search", "搜索失败: 空响应")
            val jsonObject = try {
                gson.fromJson(bodyString, JsonObject::class.java)
            } catch (_: Exception) {
                return@use toolErr("tavily_search", "搜索失败: 响应解析错误")
            }
            toolOk("tavily_search", jsonObject)
        }
    } catch (e: java.net.SocketTimeoutException) {
        toolErr("tavily_search", "搜索超时，请重试", code = "timeout")
    } catch (e: Exception) {
        toolErr("tavily_search", "搜索失败: ${e.message}")
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

    private data class PickedUriMeta(
        val displayName: String?,
        val mimeType: String?,
        val sizeBytes: Long?,
        val sourceUri: String
    )

    private data class CachedUri(
        val meta: PickedUriMeta,
        val file: File?
    )

    private data class PreparedAttachments(
        val attachments: List<ChatAttachment>,
        val injectedText: String,
        val extraImageUrls: List<String>
    )

    private fun cacheUriToCacheFile(context: Context, uri: Uri): CachedUri? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        val (displayName, sizeBytes) = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                        name to size
                    } else null to null
                } ?: (null to null)
        }.getOrDefault(null to null)

        val meta = PickedUriMeta(
            displayName = displayName,
            mimeType = mime,
            sizeBytes = sizeBytes,
            sourceUri = uri.toString()
        )

        val maxCopyBytes = 20L * 1024 * 1024
        val shouldCopy = sizeBytes == null || (sizeBytes in 1..maxCopyBytes)
        if (!shouldCopy) return CachedUri(meta, null)

        val safeBase = sanitizeFileName(displayName) ?: "attachment"
        val outFile = File(context.cacheDir, "att_${System.currentTimeMillis()}_$safeBase")
        val ok = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    var exceeded = false
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        total += read.toLong()
                        if (total > maxCopyBytes) {
                            exceeded = true
                            break
                        }
                    }
                    !exceeded
                }
            } ?: false
        }.getOrDefault(false)

        if (!ok) {
            runCatching { outFile.delete() }
            return CachedUri(meta, null)
        }
        return CachedUri(meta, outFile)
    }

    private fun sanitizeFileName(name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        val replaced = trimmed.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        return replaced.take(80).ifBlank { null }
    }

    private fun classifyAttachmentKind(displayName: String?, mimeType: String?): AttachmentKind {
        val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
        val ext = displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        if (mime.startsWith("image/")) return AttachmentKind.IMAGE
        if (mime.startsWith("audio/")) return AttachmentKind.AUDIO
        if (mime.startsWith("video/")) return AttachmentKind.VIDEO
        if (mime == "application/pdf" || ext == "pdf") return AttachmentKind.PDF
        if (mime.startsWith("text/")) return AttachmentKind.TEXT
        if (mime in setOf("application/json", "application/xml", "application/x-yaml", "application/yaml")) return AttachmentKind.TEXT
        if (ext in setOf("txt", "md", "markdown", "log", "json", "xml", "csv", "tsv", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "gradle", "kts", "java", "kt", "py", "js", "ts", "jsx", "tsx", "go", "rs", "c", "cpp", "h", "hpp", "swift", "sh", "sql")) return AttachmentKind.TEXT
        if (ext in setOf("docx", "pptx", "xlsx")) return AttachmentKind.OFFICE
        if (ext in setOf("zip", "rar", "7z", "tar", "gz")) return AttachmentKind.ARCHIVE
        return AttachmentKind.OTHER
    }

    private fun prepareAttachmentsForUserTurn(context: Context, attachments: List<ChatAttachment>): PreparedAttachments {
        val injected = StringBuilder()
        val extraImages = mutableListOf<String>()
        val kept = attachments.toList()

        val maxTotalChars = 120_000
        val maxFileBytes = 256 * 1024

        fun appendBlock(title: String, body: String) {
            if (injected.length >= maxTotalChars) return
            val remaining = (maxTotalChars - injected.length).coerceAtLeast(0)
            val t = title.take(300)
            val b = if (body.length > remaining - t.length - 50) body.take((remaining - t.length - 50).coerceAtLeast(0)) else body
            if (injected.isNotEmpty()) injected.append("\n\n")
            injected.append(t)
            if (b.isNotBlank()) {
                injected.append("\n```")
                injected.append("\n")
                injected.append(b.trim())
                injected.append("\n```")
            }
        }

        for (att in attachments) {
            if (injected.length >= maxTotalChars) break
            val name = att.displayName ?: "附件"
            when (att.kind) {
                AttachmentKind.PDF -> {
                    val rendered = renderPdfToImages(context, att, maxPages = 3)
                    if (rendered.isNotEmpty()) {
                        extraImages.addAll(rendered)
                        appendBlock("[附件: $name] PDF 已渲染前 ${rendered.size} 页为图片，随消息附上。", "")
                    } else {
                        appendBlock("[附件: $name] PDF（无法渲染，已作为文件附带元信息）", formatAttachmentMeta(att))
                    }
                }
                AttachmentKind.TEXT -> {
                    val content = readTextFromAttachment(context, att, maxBytes = maxFileBytes)
                    if (content != null) appendBlock("[附件: $name] 文本内容（最多 ${maxFileBytes / 1024}KB）", content)
                    else appendBlock("[附件: $name] 文本内容（解析失败或包含二进制）", formatAttachmentMeta(att))
                }
                AttachmentKind.OFFICE -> {
                    val content = extractOfficeOpenXmlText(context, att, maxChars = 40_000)
                    if (!content.isNullOrBlank()) appendBlock("[附件: $name] Office 内容提取（可能不完整）", content)
                    else appendBlock("[附件: $name] Office 文件（未能提取文本）", formatAttachmentMeta(att))
                }
                AttachmentKind.AUDIO -> {
                    val uri = resolveAttachmentUri(att)
                    if (uri != null) {
                        val result = executeAudioTranscribeSync(uri, null, null)
                        val text = runCatching {
                            val obj = gson.fromJson(result, JsonObject::class.java)
                            if (obj.get("ok")?.asBoolean == true) obj.getAsJsonObject("data")?.get("text")?.asString else null
                        }.getOrNull()
                        if (!text.isNullOrBlank()) appendBlock("[附件: $name] 音频转写结果", text)
                        else appendBlock("[附件: $name] 音频转写失败（原始结果）", result.take(2000))
                    } else {
                        appendBlock("[附件: $name] 音频文件（无法读取）", formatAttachmentMeta(att))
                    }
                }
                AttachmentKind.ARCHIVE -> {
                    val listed = listArchiveEntries(context, att, maxEntries = 200)
                    if (!listed.isNullOrBlank()) appendBlock("[附件: $name] 压缩包文件清单（最多 200 项）", listed)
                    else appendBlock("[附件: $name] 压缩包（无法读取）", formatAttachmentMeta(att))
                }
                else -> {
                    appendBlock("[附件: $name] 文件元信息", formatAttachmentMeta(att))
                }
            }
        }

        return PreparedAttachments(
            attachments = kept,
            injectedText = injected.toString().trim(),
            extraImageUrls = extraImages
        )
    }

    private fun resolveAttachmentUri(att: ChatAttachment): Uri? {
        if (!att.sourceUri.isNullOrBlank()) return Uri.parse(att.sourceUri)
        val p = att.localPath
        return if (!p.isNullOrBlank()) Uri.fromFile(File(p)) else null
    }

    private fun renderPdfToImages(context: Context, att: ChatAttachment, maxPages: Int): List<String> {
        val resolver = context.contentResolver
        val uri = resolveAttachmentUri(att) ?: return emptyList()
        val pfd = runCatching {
            if (uri.scheme == "file") {
                ParcelFileDescriptor.open(File(uri.path ?: return emptyList()), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                resolver.openFileDescriptor(uri, "r")
            }
        }.getOrNull() ?: return emptyList()

        return pfd.use {
            runCatching {
                val renderer = PdfRenderer(pfd)
                renderer.use {
                    val count = minOf(renderer.pageCount, maxPages.coerceAtLeast(0))
                    (0 until count).mapNotNull { idx ->
                        val page = renderer.openPage(idx)
                        page.use {
                            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val outFile = File(context.cacheDir, "pdf_${System.currentTimeMillis()}_${idx + 1}.jpg")
                            FileOutputStream(outFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            }
                            bitmap.recycle()
                            "file://${outFile.absolutePath}"
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun readTextFromAttachment(context: Context, att: ChatAttachment, maxBytes: Int): String? {
        val resolver = context.contentResolver
        val uri = resolveAttachmentUri(att) ?: return null
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(16 * 1024)
                val out = ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val allowed = minOf(read, maxBytes - total)
                    if (allowed > 0) {
                        out.write(buffer, 0, allowed)
                        total += allowed
                    }
                    if (total >= maxBytes) break
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return ""
        if (bytes.any { it.toInt() == 0 }) return null
        val text = bytes.toString(Charsets.UTF_8)
        return if (bytes.size >= maxBytes) text + "\n...(已截断)" else text
    }

    private fun extractOfficeOpenXmlText(context: Context, att: ChatAttachment, maxChars: Int): String? {
        val ext = att.displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        val uri = resolveAttachmentUri(att) ?: return null
        val resolver = context.contentResolver
        val targetEntryPredicate: (String) -> Boolean = when (ext) {
            "docx" -> { name -> name == "word/document.xml" }
            "pptx" -> { name -> name.startsWith("ppt/slides/slide") && name.endsWith(".xml") }
            "xlsx" -> { name -> name == "xl/sharedStrings.xml" }
            else -> { _ -> false }
        }
        val xmls = mutableListOf<String>()
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory && targetEntryPredicate(entry.name)) {
                            val bytes = readBytesLimited(zis, 512 * 1024)
                            val xml = bytes.toString(Charsets.UTF_8)
                            xmls.add(xml)
                            if (ext != "pptx") break
                            if (xmls.size >= 6) break
                        }
                    }
                }
            }
        }
        if (xmls.isEmpty()) return null
        val text = when (ext) {
            "docx" -> extractDocxText(xmls.first())
            "pptx" -> xmls.joinToString("\n\n") { extractPptxText(it) }
            "xlsx" -> extractXlsxSharedStrings(xmls.first())
            else -> ""
        }.trim()
        return if (text.isBlank()) null else text.take(maxChars)
    }

    private fun readBytesLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            val allowed = minOf(read, maxBytes - total)
            if (allowed > 0) {
                out.write(buffer, 0, allowed)
                total += allowed
            }
            if (total >= maxBytes) break
        }
        return out.toByteArray()
    }

    private fun extractDocxText(xml: String): String {
        val normalized = xml
            .replace(Regex("<w:br\\s*/>"), "\n")
            .replace(Regex("<w:p[^>]*>"), "\n")
        val re = Regex("<w:t[^>]*>([\\s\\S]*?)</w:t>")
        return re.findAll(normalized).joinToString("") { decodeXmlEntities(it.groupValues[1]) }
    }

    private fun extractPptxText(xml: String): String {
        val normalized = xml
            .replace(Regex("<a:br\\s*/>"), "\n")
            .replace(Regex("<a:p[^>]*>"), "\n")
        val re = Regex("<a:t[^>]*>([\\s\\S]*?)</a:t>")
        return re.findAll(normalized).joinToString("") { decodeXmlEntities(it.groupValues[1]) }
    }

    private fun extractXlsxSharedStrings(xml: String): String {
        val normalized = xml.replace(Regex("<si[^>]*>"), "\n")
        val re = Regex("<t[^>]*>([\\s\\S]*?)</t>")
        return re.findAll(normalized).joinToString("\n") { decodeXmlEntities(it.groupValues[1]).trim() }.trim()
    }

    private fun decodeXmlEntities(s: String): String {
        return s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun listArchiveEntries(context: Context, att: ChatAttachment, maxEntries: Int): String? {
        val uri = resolveAttachmentUri(att) ?: return null
        val resolver = context.contentResolver
        val names = mutableListOf<String>()
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory) {
                            names.add(entry.name)
                            if (names.size >= maxEntries) break
                        }
                    }
                }
            }
        }
        return if (names.isEmpty()) null else names.joinToString("\n")
    }

    private fun formatAttachmentMeta(att: ChatAttachment): String {
        val parts = mutableListOf<String>()
        if (!att.mimeType.isNullOrBlank()) parts.add("mime=${att.mimeType}")
        if (att.sizeBytes != null && att.sizeBytes > 0) parts.add("size=${att.sizeBytes}B")
        if (!att.localPath.isNullOrBlank()) parts.add("local=${att.localPath}")
        if (!att.sourceUri.isNullOrBlank()) parts.add("uri=${att.sourceUri}")
        return if (parts.isEmpty()) "-" else parts.joinToString(", ")
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
