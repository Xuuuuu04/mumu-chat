## 现状盘点（你这个 App 里已有的工具）
- **记忆/笔记/会话**：save_memory、get_memories、search_memories、upsert/get/rename/delete/merge/memory_gc、save_note/list/search/delete_note、search_chat_history、summarize_session_local、extract_todos_from_chat、search_sessions、rename_session、move_session_to_folder、export_session、file_export_session/file_import_session。
- **联网检索/阅读**：exa_search（需 key）、browse_url（全文抓取/策略控制）、serp_search（Baidu/DDG HTML 抓取解析）。
- **系统动作**：copy_to_clipboard、share_text、calendar_create_event、notify_set_timer、notify_schedule_at/notify_cancel_scheduled/list_scheduled_reminders。
- **媒体/计算**：calculate（JS）、text_to_image。
- **资讯/公益 API**：get_news_board、get_daily_brief、probe_public_api、get_hotlist、get_yiyan、public_translate/weather/exchange_rates/today_in_history/bing_wallpaper/epic_free_games/maoyan_boxoffice、ip_info/icp_lookup/phone_info/qr_url。
- **诊断/治理**：list_tools、get_tool_metrics/clear_tool_metrics、get_last_tool_errors、get_network_status、get_settings_summary、get_browse_policy/set_browse_policy。

## 已发现的“需要补齐/不一致”
- **ToolsCatalog 里声明了但实际未注册的日历工具**：calendar_insert_silent / calendar_list_events / calendar_delete_event（当前模型若调用会报“未知工具”）。
- **serp_search 的 resolve_redirects 参数目前属于“声明了但未生效”**（schema 有该参数，执行逻辑没用到）。

## 全量测试计划（自动化 + 手工，覆盖所有工具）
### 1) 建立“工具清单一致性”测试（自动化）
- 从 list_tools 读取 schema 清单，与 ToolEngine 实际注册的 handlers 名单做差集：
  - schema 有但未实现 → 标红
  - 实现了但 schema 没写 → 标红
- 产出一份可读的报告（JSON + Markdown）。

### 2) 为每类工具建立测试策略
- **纯本地纯函数/解析类（JVM 单测）**：
  - SERP 解析器（Baidu/DDG）、摘要/待办抽取、格式化/导出逻辑、数据结构序列化。
- **依赖网络但可 mock 的（JVM 单测 + MockWebServer）**：
  - browse_url/serp_search/public_* / get_hotlist 等：用 MockWebServer 固定返回，验证解析与错误码、超时、熔断、限流分支。
- **依赖真实网络/第三方 key 的（可跳过的 smoke test）**：
  - exa_search（无 key 自动 skip），SERP（可选择仅 DDG），并输出“本机网络可用性”结论。
- **依赖 Android 系统能力/权限/UI 的（androidTest + 手工 checklist）**：
  - clipboard/share、calendar_create_event、通知定时/取消/列出、文件导入导出。
  - 给出一套“按按钮一步步验证”的清单与预期结果（用于你在真机上快速回归）。

### 3) 统一工具返回格式校验
- 对每个工具调用结果做 JSON schema 约束：必须包含 ok/error、code、message、data/meta 等固定字段（你现有封装已接近），避免工具输出漂移。

### 4) 增加“工具自检面板”（App 内）
- 在设置页增加一个“工具自检”入口：
  - 一键跑：本地类、可 mock 的网络类、可选真实联网类
  - 输出：每个工具 PASS/FAIL、耗时、错误码、失败原因
  - 一键复制报告，方便你发我或发 issue。

## 联网调研：前沿 AI 工具/能力，你这里还缺什么（建议补充）
### A) 标准化工具生态：MCP
- 建议新增 **MCP Client**：让 App 可以接入外部 MCP Server（本地或远程），自动发现 tools/resources/prompts，把“新增工具”从写死代码变成“配置即接入”。
- 参考：MCP 规范与生态（JSON-RPC、工具/资源/提示）[1][2]。

### B) 更稳的 Web Grounding（替代/补充爬虫 SERP）
- 你现在 serp_search 属于“抓网页 + 解析”，容易遇到：验证码/结构变动/限流。
- 建议补充 **AI-native Search/Extract API**：
  - Tavily（search+extract/crawl，给结构化结果，适合 agent/RAG）[3]。
  - 或 Firecrawl/类似服务（搜索与提取一体）。
- 保留你现在的 SERP 作为“无 key 的兜底”，但把默认路径切到更稳的搜索 API（可选）。

### C) 评测与可观测（Agent/Evals）
- 你已有 tool metrics，但缺少“任务级成功率/引用质量/幻觉检测”的评测。
- 建议补充：
  - 对 browse/search 输出做引用与一致性检查（至少：URL 可达、正文包含关键断言）。
  - 可选接入 LangSmith/Langfuse 一类的 trace/eval 思路（或做轻量本地实现）。

### D) 本地/私有 RAG：文档库与向量检索
- 目前更多是“记忆/会话/笔记”检索，缺少“把 PDF/网页/文件夹索引成知识库并检索”的能力。
- 建议新增：
  - 文档导入（PDF/Markdown/网页快照）→ 分段 → embedding → 本地向量库（SQLite/HNSW 或简单倒排）
  - 新工具：ingest_docs / search_docs / cite_docs。

### E) 多模态与生产力
- 你已有 text_to_image，但缺少：
  - **语音**：STT/TTS（系统或云端），适合移动端
  - **视觉**：图片 OCR/表格提取/截图理解
  - **结构化导出**：导出到日历/待办/Notion/Obsidian（可先从本地文件协议做起）。

## 交付物（确认后我会实现/跑通）
- 一套“全工具自动化测试 + 可跳过的联网 smoke test + androidTest + 手工清单”。
- App 内“工具自检面板”。
- 修复 tools schema 与实现不一致（补齐/下架缺失工具；让 resolve_redirects 生效或移除）。
- 新增可选的“前沿能力”：MCP Client、Tavily/Firecrawl 搜索、文档库 RAG、语音/视觉。

## 你需要做的最小准备（我会在实现里尽量自动识别并提示）
- Exa/Tavily 等 key：未配置则自动跳过对应联网测试。
- 真机权限：日历/通知/文件读写（仅用于 androidTest/手工项）。

---
引用：
[1] https://modelcontextprotocol.io/specification/2025-11-25
[2] https://www.anthropic.com/news/model-context-protocol
[3] https://docs.tavily.com/faq/faq
[4] https://www.langchain.com/langgraph
[5] https://www.blog.langchain.com/langchain-langgraph-1dot0/