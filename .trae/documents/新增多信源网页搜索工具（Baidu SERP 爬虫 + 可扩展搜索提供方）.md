## 现状梳理（已阅读代码）
- 工具对模型的暴露在 [ToolsCatalog.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/tools/ToolsCatalog.kt)；工具的实际执行在 [ChatViewModel.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ChatViewModel.kt) 的 `ToolEngine.register(...)`。
- `exa_search` 目前是直连 Exa API，固定 `numResults=5`，返回 `[{rank,title,url,snippet}]`（见 [ChatViewModel.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ChatViewModel.kt#L2395-L2470)）。
- `browse_url` 会做较严格的 SSRF/内网拦截（[BrowseSafety.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/tools/BrowseSafety.kt)），并抽取正文文本。
- 工具轮次上限目前是 `MAX_TOOL_CALLS_PER_TURN = 12`（[ChatViewModel.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ChatViewModel.kt#L74-L80)）；你提到已提高到 150，落点就是这里（或把它改成 Settings 可配置）。

## 目标
- 增加“更精准、更多信源”的搜索能力：AI 能拿到「标题 + URL + 摘要/snippet」，再用 `browse_url` 精读。
- 支持爬虫式 SERP（例如 Baidu）以及可扩展的多引擎（后续可加 Sogou/360/必应/自建 searxng）。
- 保持工具返回结构化、可解析；并具备限流/缓存/熔断，避免被封或拖慢对话。

## 方案概览（新增 1～2 个搜索工具）
### 1) 新工具：`serp_search`
- 入参（建议）
  - `query: string`（必填）
  - `engine: string`（可选：`auto|baidu|bing|duckduckgo|searxng`，默认 `auto`）
  - `limit: integer`（可选，默认 8，最大 10）
  - `page: integer`（可选，默认 1）
  - `site: string`（可选，域名或站点过滤，内部转成 `site:xxx`）
- 出参（统一结构，便于 agent 精准选链接）
  - `results: [{rank,title,url,display_url?,snippet,engine,source?}]`
  - `meta: {engine, count, latency_ms, cached?, blocked_reason?}`
- 行为
  - `engine=auto`：按优先级尝试（例如 `searxng(若配置)` → `baidu` → `bing/duckduckgo`），遇到 captcha/blocked 自动降级。

### 2) 可选增强：`serp_search_multi`
- 允许 `engines: array` 一次搜多个信源并合并去重（按 url 去重，保留更靠前的 rank）；用于“信源丰富”。
- 但默认不强推，让 agent 先用单引擎，只有需要交叉验证时再多引擎。

## 关键实现细节（代码落点）
### A. Schema 暴露给模型
- 在 [ToolsCatalog.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/tools/ToolsCatalog.kt) 增加 `serp_search`（以及可选的 `serp_search_multi`）的工具定义。
- 同步更新系统提示词 `CORE_SYSTEM_PROMPT` 中的“可用工具列表”，把 `serp_search` 加进去，避免模型只盯着 `exa_search`。

### B. ToolEngine 注册与执行
- 在 [ChatViewModel.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ChatViewModel.kt) `ToolEngine.init { ... }` 增加 `register("serp_search")`。
- 该 handler 只负责：解析参数 → 调用 `SerpSearchService` → 用现有 `toolOk/toolErr` 返回 JSON。

### C. 新增可扩展“搜索提供方”结构（放到 tools 包中）
- 新建 `mumu.xsy.mumuchat.tools.search` 包（或直接放 `tools/`，但建议分包），抽象：
  - `interface SerpProvider { val id: String; suspend fun search(query, limit, page): SerpResponse }`
  - `data class SerpItem(...)` / `data class SerpResponse(...)`
  - `class SerpSearchService(providers, cache, limiter, breaker)`：负责路由（auto/降级）、合并、去重、以及统一错误码。
- 缓存/限流/熔断
  - 复用现有的 `RemoteApiCache/FixedWindowRateLimiter/SimpleCircuitBreaker`（[RemoteApiRuntime.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/tools/RemoteApiRuntime.kt)），但要支持“动态 URL + 自定义 cacheKey”（为 search 单独加一个轻量 wrapper：key=provider+query+page）。
  - 默认 TTL 5 分钟、每引擎每分钟 15 次（可后续做成设置）。

### D. Baidu SERP 爬虫（best-effort）
- 请求：`https://www.baidu.com/s?wd=<encoded>&rn=<limit>&pn=<offset>`
- Header：合理 UA（已有 `MuMuChat/2.1`）、`Accept-Language: zh-CN`；必要时增加 `Referer`。
- 解析：用 Jsoup（项目已引入）从 `#content_left` 抽取 `h3 a`、摘要块、展示链接；URL 允许返回 Baidu 的跳转链接（`/link?url=...`），后续 `browse_url` 会跟随并做安全校验。
- 反爬/验证码识别：检测页面标题/关键 DOM（如“安全验证”/`captcha`），返回 `toolErr(code="captcha")`，并在 `engine=auto` 时自动切到下一个引擎。

### E. 其他信源（可分阶段交付）
- Phase 1：先做 `baidu` + `duckduckgo`（HTML 结构相对稳定）
- Phase 2：接入 `bing` HTML 或者推荐“自建 searxng JSON API”
  - searxng 好处：稳定、可多源聚合、返回 JSON、反爬压力低。
  - 需要在设置里提供 `searxngBaseUrl`（例如 `https://search.example.com`）并在 app 内走 JSON 请求。

## 设置项（让你能控风险/开关/优先级）
- 在 [AppSettings](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ChatMessage.kt#L50-L102) 增加（建议）：
  - `enableSerpSearch: Boolean`（默认 true）
  - `serpEngineOrder: List<String>`（默认 `["searxng","baidu","duckduckgo"]`）
  - `searxngBaseUrl: String`（默认空）
- 在 [SettingsDialog.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ui/dialogs/SettingsDialog.kt) 增加 UI：开关、引擎优先级（可先简化为下拉/多选）、searxng 地址输入。

## 测试与验收
- 单元测试（`app/src/test/java`）
  - 为 Baidu/（DDG）解析器写纯解析测试：给定固定 HTML 字符串，断言提取结果数量、标题、链接非空。
- 手工验收
  - 在聊天里触发 `serp_search`，确认返回 `title/url/snippet`；把结果 URL 丢给 `browse_url` 能正常抓正文。
  - 触发 captcha 时：工具返回明确 `code=captcha`，`engine=auto` 会自动降级到其它引擎。

## 关于“爬虫/百度”风险说明（会在实现里做防护）
- Baidu SERP 可能触发验证码或封禁：所以默认有（1）低频限流（2）短 TTL 缓存（3）自动降级到其他引擎/自建 searxng。
- 我不会在日志或返回里泄露任何 cookie/密钥；也不会添加会扩大 SSRF 面的“任意 URL 搜索端点”。

## 交付顺序（确保你尽快用上）
1. 把工具轮次上限改成 150（或改成可配置）。
2. 上线 `serp_search`：`baidu` 解析 + `auto` 降级框架 + 结构化返回。
3. 增加第二信源（DDG 或 searxng），并补齐 UI 设置项与解析单测。

如果你确认这个方案，我会开始按上述顺序落地实现（会尽量把新增代码放到 `tools/search` 以减少 ChatViewModel 继续膨胀）。