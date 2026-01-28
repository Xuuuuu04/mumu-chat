# 工具测试清单（手工 + 自动化）

## 自动化测试入口
- JVM 单测：`./gradlew :app:testDebugUnitTest`
- Instrumentation：`./gradlew :app:connectedDebugAndroidTest`

说明：Instrumentation 需要连接真机或启动模拟器。

## 手工验证（需要系统权限/UI 交互）

### 日历工具
前置：
- 设置里开启“日历工具”
- 系统授予日历权限

步骤：
- 调用 `calendar_create_event`：选择“允许”，应弹出系统日历新增事件界面，标题/时间应被预填。
- 调用 `calendar_insert_silent`：选择“允许”，应返回 `event_id`；随后调用 `calendar_list_events`（范围覆盖刚才时间），应能看到该事件；最后调用 `calendar_delete_event` 删除它。

### 通知/提醒工具
前置：
- 设置里开启“通知/提醒工具”
- 系统授予通知权限（Android 13+）

步骤：
- 调用 `notify_set_timer`：选择“允许”，应弹出系统计时器界面并带入秒数/消息。
- 调用 `notify_schedule_at`：选择“允许”，应返回 `id`；随后调用 `list_scheduled_reminders` 应包含该 `id`；再调用 `notify_cancel_scheduled` 取消；最后 `list_scheduled_reminders` 不应包含该 `id`。

### 文件导入/导出
前置：
- 设置里开启“文件工具”

步骤：
- 调用 `file_export_session`：选择“允许”，应弹出保存文件界面；保存成功后应返回 `uri`。
- 调用 `file_import_session`：选择“允许”，应弹出选择文件界面；选取刚导出的 JSON，导入后应新增一个会话并切换到该会话。

### 剪贴板/分享
前置：
- 设置里开启“本地动作工具”

步骤：
- 调用 `copy_to_clipboard`：应能在系统剪贴板里粘贴出文本。
- 调用 `share_text`：应弹出系统分享面板。

## 联网工具验证（依赖网络/Key）

### SERP（无 Key）
前置：
- 设置里开启 SERP 搜索，并启用至少一个引擎（推荐 DuckDuckGo）

步骤：
- 调用 `serp_search(query=OpenAI, engine=duckduckgo)`：应返回 `results` 且每条包含 `title/url`。

### Exa（需要 Key）
前置：
- 设置里配置 Exa API Key

步骤：
- 调用 `exa_search(query=...)`：应返回结果列表；未配置 Key 时应返回“未配置 Exa Search Key”。

### browse_url（全文抓取）
步骤：
- 调用 `browse_url(url=https://example.com)`：应返回正文（如被策略阻止，调整 allowlist/denylist 或使用白名单域名验证）。

