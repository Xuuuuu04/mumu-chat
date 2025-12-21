package mumu.xsy.mumuchat

enum class MessageRole {
    USER, ASSISTANT, SYSTEM
}

data class ChatMessage(
    val content: String,
    val role: MessageRole,
    val reasoning: String? = null,
    val imageUrl: String? = null, // Base64 or local URI
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
    val selectedModel: String = "MiniMaxAI/MiniMax-M2",
    val availableModels: List<String> = listOf("MiniMaxAI/MiniMax-M2", "moonshotai/Kimi-K2-Thinking", "deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "google/gemma-2-27b-it"),
    val fetchedModels: List<String> = emptyList(),
    val systemPrompt: String = "You are MuMu AI, a helpful and creative assistant.",
    val memories: List<String> = emptyList()
)
