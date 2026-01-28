package mumu.xsy.mumuchat.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object ToolsCatalog {
    fun getToolsDefinition() = JsonArray().apply {
        add(createTool("save_memory", "记录用户信息到长期记忆。避免重复与矛盾，尽量写成简短事实。", mapOf("fact" to "string")))
        add(createTool("get_memories", "获取当前所有记忆（数组）。用于检索/核对。", emptyMap()))
        add(createTool("search_memories", "按关键词检索记忆，返回匹配项列表（含 index）。", mapOf("query" to "string")))
        add(createTool("upsert_memory", "以 key 唯一标识写入/更新记忆（避免重复/冲突）。", mapOf("key" to "string", "value" to "string")))
        add(createTool("search_chat_history", "在当前会话中按关键词检索历史消息，返回匹配片段与索引。", mapOf("query" to "string", "limit" to "integer"), required = setOf("query")))
        add(createTool("exa_search", "联网搜索", mapOf("query" to "string")))
        add(createTool("browse_url", "阅读网页全文", mapOf("url" to "string")))
        add(createTool("calculate", "执行JS计算", mapOf("code" to "string")))
        add(createTool("text_to_image", "根据描述创作图片", mapOf("prompt" to "string")))
        add(createTool("get_news_board", "获取新闻热搜(60s, weibo, zhihu, bili, douyin)", mapOf("board" to "string")))
        add(createTool("list_tools", "列出当前可用工具与参数 schema。", emptyMap()))
        add(createTool("get_tool_metrics", "获取工具调用统计（次数/错误/耗时）。", emptyMap()))
        add(createTool("get_settings_summary", "获取当前设置摘要（不包含任何密钥）。", emptyMap()))
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
