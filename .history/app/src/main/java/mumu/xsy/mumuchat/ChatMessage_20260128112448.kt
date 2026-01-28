package mumu.xsy.mumuchat

enum class StepType {
    THINKING, TOOL_CALL
}

data class ChatStep(
    val type: StepType,
    var content: String = "",
    val toolName: String? = null,
    var isFinished: Boolean = false,
    val input: String? = null,
    val output: String? = null,
    val error: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null
)

enum class MessageRole {
    USER, ASSISTANT, SYSTEM, TOOL
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val role: MessageRole,
    val steps: List<ChatStep>? = null,
    val imageUrl: String? = null,
    val imageUrls: List<String>? = null,
    val toolActions: List<String>? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String,
    val folder: String? = null, // 新增文件夹字段
    val messages: List<ChatMessage>? = null,
    val lastModified: Long = System.currentTimeMillis()
)

data class MemoryEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val key: String? = null,
    val value: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

data class McpServerConfig(
    val id: String = "default",
    val endpoint: String = "",
    val authToken: String? = null
)

data class AppSettings(
    val baseUrl: String = "https://api.siliconflow.cn/v1",
    val apiKey: String = "",
    val exaApiKey: String = "",
    val selectedModel: String = "MiniMaxAI/MiniMax-M2",
    val availableModels: List<String>? = null,
    val folders: List<String>? = null,
    val fetchedModels: List<String>? = null,
    val userPersona: String = "你是一个具备深度思考能力的超级 AI 助手“木灵”。语气专业、理性且人文，处处展现水墨雅韵之风。",
    val memories: List<String>? = null,
    val memoriesV2: List<MemoryEntry>? = null,
    val browseAllowlist: List<String>? = null,
    val browseDenylist: List<String>? = null,
    val enableLocalTools: Boolean? = null,
    val enablePublicApis: Boolean? = null,
    val enablePublicApiViki: Boolean? = null,
    val enablePublicApiTenApi: Boolean? = null,
    val enablePublicApiVvHan: Boolean? = null,
    val enablePublicApiQqsuu: Boolean? = null,
    val enablePublicApi770a: Boolean? = null,
    val enableSerpSearch: Boolean? = null,
    val enableSerpBaidu: Boolean? = null,
    val enableSerpDuckDuckGo: Boolean? = null,
    val searxngBaseUrl: String = "",
    val enableMcpTools: Boolean? = null,
    val mcpServers: List<McpServerConfig>? = null,
    val enableCalendarTools: Boolean? = null,
    val enableNotificationTools: Boolean? = null,
    val enableFileTools: Boolean? = null,
    val enableSuperIsland: Boolean? = null
) {
    fun normalized(): AppSettings {
        val legacy = memories ?: emptyList()
        val v2 = memoriesV2 ?: emptyList()
        val migratedV2 = if (v2.isEmpty() && legacy.isNotEmpty()) {
            legacy.map { legacyLine ->
                val trimmed = legacyLine.trim()
                val idx = trimmed.indexOf(':')
                if (idx in 1..60) {
                    val k = trimmed.substring(0, idx).trim().take(60)
                    val v = trimmed.substring(idx + 1).trim()
                    if (k.isNotBlank() && v.isNotBlank()) MemoryEntry(key = k, value = v) else MemoryEntry(value = trimmed)
                } else {
                    MemoryEntry(value = trimmed)
                }
            }
        } else {
            v2
        }
        return copy(
            availableModels = availableModels ?: DEFAULT_AVAILABLE_MODELS,
            folders = folders ?: DEFAULT_FOLDERS,
            fetchedModels = fetchedModels ?: emptyList(),
            memories = legacy,
            memoriesV2 = migratedV2,
            browseAllowlist = browseAllowlist ?: emptyList(),
            browseDenylist = browseDenylist ?: emptyList(),
            enableLocalTools = enableLocalTools ?: false,
            enablePublicApis = enablePublicApis ?: true,
            enablePublicApiViki = enablePublicApiViki ?: true,
            enablePublicApiTenApi = enablePublicApiTenApi ?: true,
            enablePublicApiVvHan = enablePublicApiVvHan ?: false,
            enablePublicApiQqsuu = enablePublicApiQqsuu ?: false,
            enablePublicApi770a = enablePublicApi770a ?: false,
            enableSerpSearch = enableSerpSearch ?: false,
            enableSerpBaidu = enableSerpBaidu ?: false,
            enableSerpDuckDuckGo = enableSerpDuckDuckGo ?: false,
            enableMcpTools = enableMcpTools ?: false,
            mcpServers = mcpServers ?: emptyList(),
            enableCalendarTools = enableCalendarTools ?: false,
            enableNotificationTools = enableNotificationTools ?: false,
            enableFileTools = enableFileTools ?: false,
            enableSuperIsland = enableSuperIsland ?: false
        )
    }
}

private val DEFAULT_AVAILABLE_MODELS = listOf(
    "deepseek-ai/DeepSeek-V3.2",
    "deepseek-ai/DeepSeek-R1",
    "moonshotai/Kimi-K2-Thinking",
    "MiniMaxAI/MiniMax-M2",
    "zai-org/GLM-4.6V",
    "zai-org/GLM-4.6",
    "Qwen/Qwen3-VL-32B-Thinking",
    "Qwen/Qwen3-Omni-30B-A3B-Thinking",
    "Qwen/Qwen3-Omni-30B-A3B-Captioner",
    "Qwen/Qwen3-Next-80B-A3B-Thinking",
    "ByteDance-Seed/Seed-OSS-36B-Instruct"
)

private val DEFAULT_FOLDERS = listOf("生活", "工作", "学习")
