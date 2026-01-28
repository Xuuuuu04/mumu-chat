package mumu.xsy.mumuchat.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object ToolsCatalog {
    fun getToolsDefinition() = JsonArray().apply {
        add(createTool("save_memory", "记录用户信息到长期记忆。避免重复与矛盾，尽量写成简短事实。", mapOf("fact" to "string")))
        add(createTool("get_memories", "获取当前所有记忆（数组）。用于检索/核对。", emptyMap()))
        add(createTool("exa_search", "联网搜索", mapOf("query" to "string")))
        add(createTool("browse_url", "阅读网页全文", mapOf("url" to "string")))
        add(createTool("calculate", "执行JS计算", mapOf("code" to "string")))
        add(createTool("text_to_image", "根据描述创作图片", mapOf("prompt" to "string")))
        add(createTool("get_news_board", "获取新闻热搜(60s, weibo, zhihu, bili, douyin)", mapOf("board" to "string")))
        add(createTool("delete_memory", "删除记忆。index 为 0 基索引，对应 get_memories 返回数组下标。", mapOf("index" to "integer")))
        add(createTool("update_memory", "更新记忆。index 为 0 基索引；text 为新内容。", mapOf("index" to "integer", "text" to "string")))
    }

    private fun createTool(name: String, desc: String, props: Map<String, String>) = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name)
            addProperty("description", desc)
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                val pObj = JsonObject()
                props.forEach { (k, v) -> pObj.add(k, JsonObject().apply { addProperty("type", v) }) }
                add("properties", pObj)
                if (props.isNotEmpty()) {
                    add("required", JsonArray().apply { props.keys.forEach { add(it) } })
                }
            })
        })
    }
}
