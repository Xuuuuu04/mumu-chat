## 现状与结论
- 当前已具备：多轮工具调用链、统一 JSON 输出、工具级超时、工具指标/错误回放、browse SSRF 防护（含 allow/deny）、跨会话导出与搜索、结构化记忆、部分本地动作（剪贴板/分享）与本地笔记。
- 你的目标是进一步让 Agent“像手机助理一样”可调度系统能力，同时把稳定的公益 API（如 60s）按可控方式集成，并且“限制过大的不要”。

## 公益API端点的取舍（基于已知可用性）
- 保留并作为主源：`https://60s.viki.moe/v2/60s` 与 `?encoding=text`（返回稳定且结构完整）。
- 备用镜像：`https://60s-api.114128.xyz/v2/60s`（同结构，适合作为降级）。
- 暂不直接集成：`http://api.03c3.cn/zb`（当前返回 404，不满足“稳定运行”要求）；后续若要纳入，将先做探活与自动熔断。
- 其它第三方（TenAPI/vvhan 等）：先“白名单 + 限速 + 缓存 + 熔断”框架化接入，再按探活结果决定是否默认启用。

## 设计原则（避免后台不可见动作）
- 默认关闭所有“本地系统动作类工具”（日历/通知/文件/剪贴板/分享等），并在设置页显式授权开关。
- 所有需要用户交互的系统能力，统一走“可确认的 Intent 流程”或“延迟动作队列（Action Queue）”：工具只创建待执行动作并返回 action_id，UI 弹窗让用户确认后才执行。
- 统一安全策略：域名 allow/deny、SSRF/内网阻断、响应大小限制、重定向 hop 限制、超时、速率限制、缓存 TTL、失败熔断。

## 需要新增/升级的工具清单
### A. 手机系统能力（P2 但会按安全策略实现）
- **日历**：`create_calendar_event`（使用 ACTION_INSERT/CalendarContract，强制弹出系统确认页；无需后台写入）。
- **通知**：`schedule_notification`（需要 POST_NOTIFICATIONS；同样默认关闭，并在 UI 申请权限；动作必须可在“最近动作”追踪）。
- **文件导入/导出**：`import_file` / `export_file`（使用 SAF 打开/保存文档，必须用户选择文件；工具返回 action_id，UI 完成交互后继续工具链）。
- **笔记增强**：`update_note`、`get_note`、`export_note`（与现有 NotesStore 体系一致）。

### B. 公益信息与热榜工具（P1/P0）
- **Daily 60s 聚合**：`get_daily_brief(source, format)`
  - source：viki / 114128 / vvhan_image（按探活与失败熔断自动降级）
  - format：json/text/image_url（对 image 301 跟随，按 hop 限制）
- **60s 扩展工具**（按探活后决定默认启用）：`translate`、`weather`、`exchange_rates`、`today_in_history`、`bing_wallpaper`、`epic_free_games`、`maoyan_boxoffice`。
- **热搜榜**：`get_hotlist(platform, source)`（优先 TenAPI；对调用频率做节流与缓存）。

### C. 统一“远程接口执行框架”（P0）
- 新增 RemoteApiRegistry：每个 endpoint 描述（host、路径、方法、参数 schema、返回解析、TTL、速率限制、失败熔断策略）。
- 新增 `probe_public_api`：探活工具（仅 GET/HEAD，不带敏感信息），将健康状态与失败原因记录到工具指标里。

## UI/可控性改造（P1）
- 在设置页新增：
  - “本地动作工具开关”细分：日历/通知/文件/剪贴板/分享分别开关。
  - 公益 API 的源选择与“启用/禁用列表”。
  - 浏览 allow/deny 已有：补充示例与“探活结果”展示。
  - 工具指标/最近错误已可视化：补充“最近动作日志”（包含 action_id、类型、时间、结果）。

## 测试与验证（P0）
- 单元测试：
  - BrowseSafety（IPv6、deny 优先、子域匹配、DNS 失败）继续扩充用例。
  - RedirectFollower（hop 限制、无 Location、解析失败）继续扩充。
  - TextTruncate（边界值）继续扩充。
  - 新增 RemoteApiRegistry 的 rate-limit/TTL/熔断状态机测试。
- 集成测试（建议引入 MockWebServer）：
  - 模拟 301/302、超时、5xx、返回超大 body，验证统一超时/大小限制/熔断是否生效。

## 交付方式
- 分三批落地，保证每批都可编译+可测试：
  1) RemoteApiRegistry + 60s 聚合 + 探活/熔断（先只接入 viki 与 114128）。
  2) TenAPI/vvhan 等热榜与工具端点（按探活结果决定默认启用）。
  3) 手机系统能力工具（Calendar/Notification/SAF 文件），引入 Action Queue 与 UI 确认流。
