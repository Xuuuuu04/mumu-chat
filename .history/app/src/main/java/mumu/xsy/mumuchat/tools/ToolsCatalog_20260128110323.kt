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
        add(createTool("save_note", "保存一条本地笔记。", mapOf("title" to "string", "content" to "string"), required = emptySet()))
        add(createTool("list_notes", "列出本地笔记（可选 limit）。", mapOf("limit" to "integer"), required = emptySet()))
        add(createTool("search_notes", "搜索本地笔记。", mapOf("query" to "string", "limit" to "integer"), required = setOf("query")))
        add(createTool("delete_note", "删除本地笔记（按 id）。", mapOf("id" to "string")))
        add(createTool("copy_to_clipboard", "复制文本到剪贴板（需要启用本地工具开关）。", mapOf("text" to "string")))
        add(createTool("share_text", "系统分享文本（需要启用本地工具开关）。", mapOf("text" to "string", "title" to "string"), required = setOf("text")))
        add(createTool("search_sessions", "全局搜索会话内容，返回匹配的会话与片段。", mapOf("query" to "string", "limit" to "integer"), required = setOf("query")))
        add(createTool("rename_session", "重命名会话。", mapOf("session_id" to "string", "title" to "string")))
        add(createTool("move_session_to_folder", "把会话移动到指定文件夹（folder 为空表示移出）。", mapOf("session_id" to "string", "folder" to "string"), required = setOf("session_id")))
        add(createTool("export_session", "导出会话（format=markdown/plain/html/pdf）。", mapOf("session_id" to "string", "format" to "string"), required = emptySet()))
        add(createTool("exa_search", "联网搜索", mapOf("query" to "string")))
        add(createTool("browse_url", "阅读网页全文", mapOf("url" to "string")))
        add(createTool("serp_search", "爬虫式 SERP 搜索（返回标题/URL/snippet，用于精读前的链接筛选）。", mapOf(
            "query" to "string",
            "engine" to "string",
            "limit" to "integer",
            "page" to "integer",
            "site" to "string",
            "resolve_redirects" to "boolean"
        ), required = setOf("query")))
        add(createTool("calendar_create_event", "创建日历事件（会弹出系统日历编辑界面）。", mapOf(
            "title" to "string",
            "start_ms" to "integer",
            "end_ms" to "integer",
            "location" to "string",
            "notes" to "string"
        ), required = setOf("title", "start_ms", "end_ms")))
        add(createTool("calendar_insert_silent", "直接写入日历事件（需要日历权限）。", mapOf(
            "title" to "string",
            "start_ms" to "integer",
            "end_ms" to "integer",
            "location" to "string",
            "notes" to "string"
        ), required = setOf("title", "start_ms", "end_ms")))
        add(createTool("calendar_list_events", "查询日历事件列表（需要日历权限）。", mapOf(
            "start_ms" to "integer",
            "end_ms" to "integer",
            "limit" to "integer"
        ), required = setOf("start_ms", "end_ms")))
        add(createTool("calendar_delete_event", "删除日历事件（需要日历权限）。", mapOf("event_id" to "string"), required = setOf("event_id")))
        add(createTool("notify_set_timer", "设置倒计时提醒（会弹出系统计时器界面）。", mapOf(
            "seconds" to "integer",
            "message" to "string"
        ), required = setOf("seconds")))
        add(createTool("notify_schedule_at", "创建定时提醒（后台触发通知）。", mapOf(
            "title" to "string",
            "text" to "string",
            "at_ms" to "integer"
        ), required = setOf("title", "at_ms")))
        add(createTool("notify_cancel_scheduled", "取消定时提醒（按 id）。", mapOf("id" to "string"), required = setOf("id")))
        add(createTool("list_scheduled_reminders", "列出当前所有定时提醒。", emptyMap()))
        add(createTool("file_export_session", "导出会话为 JSON 文件（会弹出系统保存文件界面）。", mapOf(
            "session_id" to "string"
        ), required = emptySet()))
        add(createTool("file_import_session", "从文件导入会话 JSON（会弹出系统选择文件界面）。", emptyMap()))
        add(createTool("calculate", "执行JS计算", mapOf("code" to "string")))
        add(createTool("text_to_image", "根据描述创作图片", mapOf("prompt" to "string")))
        add(createTool("get_news_board", "获取新闻热搜(60s, weibo, zhihu, bili, douyin)", mapOf("board" to "string")))
        add(createTool("get_daily_brief", "获取每日 60s 简报（source=viki/114128/vvhan/qqsuu/770a，format=json/text/image_url；部分 source 仅支持图片）。", mapOf("source" to "string", "format" to "string"), required = emptySet()))
        add(createTool("probe_public_api", "探活公益 API 端点（返回延迟/状态，用于自动降级）。", mapOf("endpoint_id" to "string"), required = emptySet()))
        add(createTool("get_hotlist", "获取热搜榜（platform=weibo/zhihu/douyin/baidu/toutiao/bilibili，默认 TenAPI）。", mapOf("platform" to "string"), required = setOf("platform")))
        add(createTool("get_yiyan", "获取随机一言（默认 TenAPI）。", emptyMap()))
        add(createTool("public_translate", "公益翻译（text,to）。", mapOf("text" to "string", "to" to "string"), required = setOf("text")))
        add(createTool("public_weather", "公益天气查询（query=城市）。", mapOf("query" to "string"), required = setOf("query")))
        add(createTool("public_exchange_rates", "公益汇率换算（c=币种代码，例如 CNY）。", mapOf("c" to "string"), required = emptySet()))
        add(createTool("public_today_in_history", "历史上的今天（公益接口）。", emptyMap()))
        add(createTool("public_bing_wallpaper", "必应壁纸（返回直链/信息）。", emptyMap()))
        add(createTool("public_epic_free_games", "Epic 免费游戏列表（公益接口）。", emptyMap()))
        add(createTool("public_maoyan_boxoffice", "猫眼票房（公益接口）。", emptyMap()))
        add(createTool("ip_info", "IP 归属地查询（vvhan）。", mapOf("ip" to "string"), required = setOf("ip")))
        add(createTool("icp_lookup", "ICP 备案查询（vvhan）。", mapOf("domain" to "string"), required = setOf("domain")))
        add(createTool("phone_info", "手机号归属地（vvhan）。", mapOf("tel" to "string"), required = setOf("tel")))
        add(createTool("qr_url", "生成二维码图片 URL（vvhan）。", mapOf("text" to "string"), required = setOf("text")))
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
