package mumu.xsy.mumuchat

enum class StepType {
    THINKING, TOOL_CALL
}

data class ChatStep(
    val type: StepType,
    var content: String = "",
    val toolName: String? = null,
    var isFinished: Boolean = false
)

enum class MessageRole {
    USER, ASSISTANT, SYSTEM, TOOL
}

data class ChatMessage(
    val content: String,
    val role: MessageRole,
    val steps: List<ChatStep> = emptyList(),
    val imageUrl: String? = null,
    val toolActions: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val lastModified: Long = System.currentTimeMillis()
)

data class AppSettings(
    val baseUrl: String = "https://api.siliconflow.cn/v1",
    val apiKey: String = "sk-kjfvtxdspxngnsgsmeciaycwitfpuyvnybokuivrliquzbbt",
    val exaApiKey: String = "6112f5dc-bc6e-4632-aee0-b9d62afa6b41",
    val selectedModel: String = "MiniMaxAI/MiniMax-M2",
    val availableModels: List<String> = listOf("MiniMaxAI/MiniMax-M2", "moonshotai/Kimi-K2-Thinking", "deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "google/gemma-2-27b-it"),
    val fetchedModels: List<String> = emptyList(),
    val systemPrompt: String = """你是一个名为 MuMu AI 的智能助手。
当前系统时间: {CURRENT_TIME}
核心准则：
1. 尽可能诚实，坚决杜绝幻觉。如果涉及不清楚的问题、事实核查或时效性极强的问题（如新闻、股价、天气等），请务必调用 exa_search 工具进行多轮搜索确认。
2. 严禁在调用工具的同时输出任何自然语言文本。
3. 只有在所有工具执行完毕后，才在最后一轮回复中用自然语言对用户进行详细、准确的最终解释。
4. 如果搜索结果不充分，可以进行多轮不同维度的搜索。""",
    val memories: List<String> = emptyList()
)
