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
    var title: String,
    val folder: String? = null, // 新增文件夹字段
    val messages: List<ChatMessage> = emptyList(),
    val lastModified: Long = System.currentTimeMillis()
)

data class AppSettings(
    val baseUrl: String = "https://api.siliconflow.cn/v1",
    val apiKey: String = "sk-kjfvtxdspxngnsgsmeciaycwitfpuyvnybokuivrliquzbbt",
    val exaApiKey: String = "6112f5dc-bc6e-4632-aee0-b9d62afa6b41",
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
    val systemPrompt: String = """你是一个具备深度思考能力的超级 AI 助手 MuMu。
当前系统时间: {CURRENT_TIME}

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
- 引用搜索来源（如果有）。
- 语气专业、理性且人文。""",
    val memories: List<String> = emptyList()
)
