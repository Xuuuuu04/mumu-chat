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
    val steps: List<ChatStep> = emptyList(),
    val imageUrl: String? = null,
    val imageUrls: List<String> = emptyList(),
    val toolActions: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String,
    val folder: String? = null, // 新增文件夹字段
    val messages: List<ChatMessage> = emptyList(),
    val lastModified: Long = System.currentTimeMillis()
)

data class AppSettings(
    val baseUrl: String = "https://api.siliconflow.cn/v1",
    val apiKey: String = "",
    val exaApiKey: String = "",
    val selectedModel: String = "MiniMaxAI/MiniMax-M2",
    val availableModels: List<String> = listOf(
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
    ),
    val folders: List<String> = listOf("生活", "工作", "学习"),
    val fetchedModels: List<String> = emptyList(),
    val userPersona: String = "你是一个具备深度思考能力的超级 AI 助手 MuMu。语气专业、理性且人文。",
    val memories: List<String> = emptyList()
)
