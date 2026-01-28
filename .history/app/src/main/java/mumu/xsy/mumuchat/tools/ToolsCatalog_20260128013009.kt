package mumu.xsy.mumuchat.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object ToolsCatalog {
    fun getToolsDefinition() = JsonArray().apply {
        add(createTool("save_memory", "记录用户信息到长期记忆。避免重复与矛盾，尽量写成简短事实。", mapOf("fact" to "string")))
        add(createTool("get_memories", "获取当前所有记忆（数组）。用于检索/核对。", emptyMap()))
        add(createTool("search_memories", "按关键词检索记忆，返回匹配项列表（含 index）。", mapOf("query" to "string")))
        add(createTool("upsert_memory", "以 key 唯一标识写入/更新记忆（避免重复/冲突）。", mapOf("key" to "string", "value" to "string")))
        add(createTool("get_memory", "按 key 或 index 获取单条记忆。优先使用 key。", mapOf("key" to "string", "index" to "integer"), required = emptySet()))
        add(createTool("delete_memory_by_key", "按 key 删除记忆。", mapOf("key" to "string")))
        add(createTool("list_memory_keys", "列出所有记忆 key（去重、按字母排序）。", emptyMap()))
        add(createTool("rename_memory_key", "重命名记忆 key（如新 key 已存在则合并）。", mapOf("old_key" to "string", "new_key" to "string")))
        add(createTool("merge_memories", "合并重复记忆（按 key 去重，保留更新时间最新）。", emptyMap()))
        add(createTool("memory_gc", "清理记忆（按策略裁剪）。", mapOf("max_entries" to "integer", "keep_recent_days" to "integer"), required = emptySet()))
        add(createTool("search_chat_history", "在当前会话中按关键词检索历史消息，返回匹配片段与索引。", mapOf("query" to "string", "limit" to "integer"), required = setOf("query")))
        add(createTool("summarize_session_local", "对当前会话做本地总结（不联网、不调用模型）。", mapOf("limit" to "integer"), required = emptySet()))
        add(createTool("extract_todos_from_chat", "从当前会话抽取待办（本地规则）。", mapOf("limit" to "integer"), required = emptySet()))
        add(createTool("exa_search", "联网搜索", mapOf("query" to "string")))
        add(createTool("browse_url", "阅读网页全文", mapOf("url" to "string")))
        add(createTool("calculate", "执行JS计算", mapOf("code" to "string")))
        add(createTool("text_to_image", "根据描述创作图片", mapOf("prompt" to "string")))
        add(createTool("get_news_board", "获取新闻热搜(60s, weibo, zhihu, bili, douyin)", mapOf("board" to "string")))
        add(createTool("get_session_stats", "获取当前会话统计（消息数/角色分布/时间范围）。", mapOf("limit" to "integer"), required = emptySet()))
        add(createTool("list_tools", "列出当前可用工具与参数 schema。", emptyMap()))
        add(createTool("get_tool_metrics", "获取工具调用统计（次数/错误/耗时）。", emptyMap()))
        add(createTool("clear_tool_metrics", "清空工具调用统计与错误记录。", emptyMap()))
        add(createTool("get_last_tool_errors", "获取最近工具错误列表。", mapOf("limit" to "integer"), required = emptySet()))
        add(createTool("get_network_status", "获取网络/可达性摘要（不包含敏感信息）。", emptyMap()))
        add(createTool("get_settings_summary", "获取当前设置摘要（不包含任何密钥）。", emptyMap()))
        add(createTool("get_browse_policy", "获取 browse_url 策略（allowlist/denylist）。", emptyMap()))
        add(createTool("set_browse_policy", "设置 browse_url 策略（allowlist/denylist）。", mapOf("allowlist" to "array", "denylist" to "array"), required = emptySet()))
        add(createTool("delete_memory", "删除记忆。index 为 0 基索引，对应 get_memories 返回数组下标。", mapOf("index" to "integer")))
        add(createTool("update_memory", "更新记忆。index 为 0 基索引；text 为新内容。", mapOf("index" to "integer", "text" to "string")))
    }

    private fun createTool(name: String, desc: String, props: Map<String, String>, required: Set<String> = props.keys) = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name)
            addProperty("description", desc)
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                val pObj = JsonObject()
                props.forEach { (k, v) -> pObj.add(k, JsonObject().apply { addProperty("type", v) }) }
                add("properties", pObj)
                if (required.isNotEmpty()) {
                    add("required", JsonArray().apply { required.forEach { add(it) } })
                }
            })
        })
    }
}
