## 现状判断（为什么“倒计时没在系统时钟里跑”）
- 你现在的 `notify_set_timer` 是用 `AlarmClock.ACTION_SET_TIMER` 打开系统计时器界面，但传了 `EXTRA_SKIP_UI=false`，这意味着**系统/时钟 App 允许弹 UI 而不必立即开始倒计时**；很多实现需要用户再点一次“开始”。
- Android 官方规范里，`ACTION_SET_TIMER` 支持 `EXTRA_LENGTH(秒)` 与 `EXTRA_SKIP_UI`；当 `EXTRA_SKIP_UI=true` 且长度给定时，时钟实现应直接创建/复用并启动计时器（系统层倒计时，不是应用内倒计时）。(参考：AlarmClock 文档与 Common Intents 示例，均明确了 `EXTRA_SKIP_UI` 用法与 timer 行为) https://www.android-doc.com/reference/android/provider/AlarmClock.html ；https://spot.pcc.edu/~mgoodman/developer.android.com/guide/components/intents-common.html
- 另外，Common Intents 示例还提示：调用 `ACTION_SET_TIMER/ACTION_SET_ALARM` 需要声明 `com.android.alarm.permission.SET_ALARM`（不同 ROM 对是否严格检查不一致，小米/澎湃OS 更可能严格），否则可能“打开了但不生效/不启动”。https://spot.pcc.edu/~mgoodman/developer.android.com/guide/components/intents-common.html

## 目标
把所有“系统交互工具”升级为：
- **优先与系统/小米原生 App 交互**（时钟/日历/通知设置/省电与自启动等），不在应用内模拟。
- **每个交互都有可用性探测 + 小米优先路由 + 失败降级**（不会出现“看似调用了但没效果”的黑盒体验）。

## 要改的点（按模块）
### 1) 倒计时/闹钟：改为真正触发系统时钟开始计时
- `notify_set_timer`：
  - 当用户传了 `seconds` 时：改为 `AlarmClock.ACTION_SET_TIMER` + `EXTRA_LENGTH=seconds` + `EXTRA_MESSAGE` + `EXTRA_SKIP_UI=true`，让系统时钟直接启动计时器。
  - 若系统/小米时钟拒绝跳过 UI（resolve 失败或抛异常）：自动降级为 `EXTRA_SKIP_UI=false` 打开计时器页面，并提示用户点击开始。
  - 加入“优先小米时钟包名”的路由：若设备上存在 `com.android.deskclock`（澎湃/MIUI 时钟常见包名），先 `intent.setPackage(...)`，再 fallback 到系统默认解析（避免被第三方时钟接管导致行为不一致）。
- Manifest：补齐 `com.android.alarm.permission.SET_ALARM`（按 Common Intents 规范）。

### 2) 日历：优先打开/交给小米日历 App，并增强静默写入可靠性
- `calendar_create_event`（打开系统日历编辑页）：
  - 增加 `resolveActivity` 检查 + 失败降级。
  - 若存在小米日历包（国际版常见 `com.xiaomi.calendar`，不同地区可能不同），优先 setPackage，再 fallback 默认解析。
- `calendar_insert_silent`（CalendarContract 写入）：
  - 改进“可写日历”选择：过滤只读日历（用 CALENDAR_ACCESS_LEVEL/OWNER_ACCOUNT 等字段），避免“写入失败但无反馈”。
  - 写入后返回 eventId 并在最近动作中记录来源日历 id，便于审计与删除。

### 3) 通知/权限/系统设置跳转：小米优先 + 标准回退
- 通知设置：保留 `ACTION_APP_NOTIFICATION_SETTINGS`，补一个回退到 `ACTION_APPLICATION_DETAILS_SETTINGS`（ROM 不支持时也能直达应用详情）。
- 省电与自启动：增加“打开 MIUI 自启动管理页”的快捷入口（如果该 Activity 存在），否则回退到系统电池优化/应用详情页。MIUI 常见入口在 `com.miui.securitycenter`（以 `resolveActivity` 探测为准）。

### 4) 统一系统交互层（避免散落各处）
- 新增一个 `SystemIntents`/`SystemIntegration` 工具类：
  - 负责：
    - 查找可处理 Activity（PackageManager query/resolve）
    - 小米优先选择（时钟/日历/安全中心等）
    - 安全 startActivity（try/catch ActivityNotFoundException）
    - 返回结构化结果（是否成功、使用了哪个包/组件、失败原因）
- `ChatViewModel` 的工具实现全部改调用该统一层，确保行为一致。

### 5) 增加“系统交互自检”与可观测性
- 在设置页/诊断页增加“系统交互自检”：
  - 检查：时钟 timer/alarm、日历 insert、通知设置页、省电/自启动页是否可解析。
  - 输出：解析到的组件包名/Activity 名称，帮助你在小米机上快速确认到底被哪个系统 App 接管。

## 验证方式
- 本地编译：`testDebugUnitTest`。
- 真机验证（小米/澎湃OS3）：
  - 调用 `notify_set_timer(seconds=60)` 后，系统时钟应直接出现并开始 1 分钟倒计时。
  - `calendar_create_event` 应优先打开小米日历编辑页（若存在），否则打开系统默认日历。
  - 自检面板能显示每项系统交互的“可解析/不可解析 + 组件信息”。

## 将改动到的文件（预期）
- 逻辑：`ChatViewModel.kt`
- 资源/权限：`AndroidManifest.xml`
- 新增：`system/SystemIntents.kt`（或类似命名）
- UI：`SettingsDialog.kt`（加自检与快捷入口）

确认后我就开始按以上清单逐项落地实现，优先把“倒计时必定在系统时钟里跑起来”这件事修到可复现可验证。