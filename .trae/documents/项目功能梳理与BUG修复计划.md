# 项目功能梳理与BUG修复计划

## 当前项目功能（我已确认的实现点）
- Android Kotlin 单模块应用（app），UI 用 Jetpack Compose，状态集中在 ChatViewModel。
- OpenAI-compatible Chat Completions（SSE 流式），支持 reasoning_content（思考）与 tool_calls（工具）。
- 工具能力：exa_search、browse_url（Jsoup 抽取正文）、calculate（Rhino）、text_to_image、get_news_board、memory CRUD。
- 会话管理：新建/重命名/删除/文件夹分类；会话用 DataStore(Preferences)+Gson 持久化；设置用 SharedPreferences+Gson。

## 已定位的高确定性 BUG（可复现/逻辑确定）
- Step 更新会“污染”整段会话所有消息：updateStep 调用 updateMessage(sIdx) 未传 msgIdx，导致把同一 steps 写到该会话