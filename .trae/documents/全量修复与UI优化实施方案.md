# 全量修复与UI优化实施方案

## 目标与约束
- 目标：修复已发现的全部稳定复现/高风险问题；全面优化 UI 细节与交互一致性；在不牺牲可维护性的前提下为功能拓展打好结构基础；全程保证可编译。
- 约束：沿用现有技术栈（Compose + Material3、OkHttp SSE、DataStore + Gson），优先小步可验证的重构；尽量复用现有组件与主题体系。

## 第一阶段：功能性 Bug 全量修复（优先级 P0）
1. 修复 DataStore 会话加载的线程安全与竞态
   - 现状：`loadSessionsFromDataStore()` 在 IO 线程直接 `sessions.clear()/addAll()`，且 `init` 中先创建默认会话再异步加载，存在竞态与 Snapshot 崩溃风险。
   - 改法：
     - 将 `sessions/currentSessionId` 的写入统一切到 Main。
     - 初始化流程改为：先加载 → 若为空再创建首会话；并确保 `currentSessionId` 始终指向有效会话。
   - 影响文件：[ChatViewModel.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ChatViewModel.kt#L111-L140)

2. 修复 HTML 预览实际不加载/不刷新内容
   - 现状：`AndroidView(update=...)` 的条件判断导致几乎不触发 `loadDataWithBaseURL`。
   - 改法：以 `htmlContent` 为驱动（`LaunchedEffect(htmlContent)` 或直接 `update` 中 `view.loadDataWithBaseURL(...)`）。
   - 同步优化：暗色模式适配（避免强制白底黑字）。
   - 影响文件：[ChatScreen.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ChatScreen.kt#L109-L186)

3. 修复消息列表 key 的非空类型错误
   - 现状：`msg.id` 非空却使用 `?:`，属于不正确写法，且易导致编译/逻辑混乱。
   - 改法：统一使用稳定 key（`msg.id`）。
   - 影响文件：[MessageBubbles.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ui/components/MessageBubbles.kt#L68-L72)

4. 修复删除文件夹不会解绑会话 folder 的逻辑错误
   - 现状：`copy(folder=null)` 未写回 `sessions`。
   - 改法：按索引更新列表元素并持久化。
   - 影响文件：[ChatViewModel.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ChatViewModel.kt#L186-L194)

5. 校验并回归：step 更新污染问题
   - 说明：此前已定位 `updateStep` 写回会话全量消息的问题，下一步会将其纳入回归验证（确保所有步骤只作用于目标消息）。

## 第二阶段：性能与稳定性优化（优先级 P1）
1. 消除流式输出时的 DataStore 写入风暴
   - 现状：`updateMessage()` 每次增量都 `saveSessionsToDataStore()`，SSE 下会极高频。
   - 改法：
     - 引入节流/合并保存策略（例如 800ms~1500ms 合并一次）+ 回合结束强制保存。
     - 将“UI 中间态更新”和“持久化落盘”解耦（保证顺滑但不丢数据）。
   - 影响文件：[ChatViewModel.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ChatViewModel.kt#L1050-L1070)

2. 降低 Markdown 在流式阶段的重渲染成本
   - 改法：流式进行中使用轻量 Text（或节流后再触发 Markdown），流式结束后再切回 Markdown 渲染。
   - 影响文件：[MessageBubbles.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ui/components/MessageBubbles.kt)

3. 附件与图片的内存/耗时风险控制
   - 文件附件：限制大小与类型，迁移到协程 IO 读取，主线程只更新最终文本；对二进制文件给出可读错误提示。
   - 图片：对 Base64 编码增加大小上限/更强的压缩与失败提示，避免 OOM。
   - 影响文件：[ChatInputDock.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ui/components/ChatInputDock.kt#L49-L62)，[ChatViewModel.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ChatViewModel.kt#L1072-L1101)

4. 自动滚动策略优化
   - 改法：当用户接近底部时，监听最后一条消息内容长度变化（节流）进行跟随；用户上滑阅读时不强制拉回底部。
   - 影响文件：[MessageBubbles.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ui/components/MessageBubbles.kt#L55-L60)

## 第三阶段：UI 细节全面优化（视觉一致性 + 交互可用性）
1. 导出能力做“真实可用”
   - 改法：
     - 真正写入剪贴板（Markdown）。
     - 引入 `SnackbarHost` 在侧边栏或主 Scaffold 统一展示提示。
     - 进一步支持“分享文件/保存到 Downloads”（后续拓展点）。
   - 影响文件：[SidebarContent.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ui/components/SidebarContent.kt#L85-L101)

2. 侧边栏文件夹与会话列表的状态正确性与可用性
   - 为 `LazyColumn` 中会话项与 folder 项加稳定 key；folder 展开状态改为以 folderName 为 key 的状态 Map（避免重组错位）。
   - 改进命中区/间距/层级对比（folder header、会话项、数量 badge、more 菜单）。
   - 影响文件：[SidebarContent.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ui/components/SidebarContent.kt#L130-L223)

3. 输入栏与设置面板的细节优化
   - 输入栏：发送按钮 disabled 状态的对比度；附件菜单图标语义；图片预览的边框与删除按钮命中区；错误提示的视觉统一。
   - 设置：API Key/Exa Key 使用密码样式可见性切换；Base URL 校验与示例提示；模型列表的分组与搜索。
   - 影响文件：[ChatInputDock.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ui/components/ChatInputDock.kt)，[SettingsDialog.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ui/dialogs/SettingsDialog.kt)

4. HTML 预览对话框的主题与可读性
   - 跟随 Material3 主题（暗色/亮色）；提供“复制 HTML/在外部浏览器打开”的快捷动作。
   - 影响文件：[ChatScreen.kt](file:///Users/xushaoyang/Desktop/mumuchat/app/src/main/java/mumu/xsy/mumuchat/ChatScreen.kt)

## 第四阶段：结构重构（让后续功能拓展可维护）
1. 拆分 ChatViewModel 的职责（保持 API 尽量不破坏 UI）
   - 抽离成独立类：
     - `SessionsStore`（DataStore 读写、节流保存、迁移兼容）
     - `SettingsStore`（SharedPreferences 读写）
     - `ChatApi`（构建请求、SSE 解析/回调）
     - `ToolExecutor`（工具定义 + 执行分发）
     - `ExportService`（导出/复制/分享）
   - ViewModel 只负责：状态编排、事件处理、协调依赖。

2. 状态模型整理
   - 明确 UI 可观察状态：`sessions/currentSessionId/inputDraft/selectedImageUri/isStreaming/error` 等，减少隐式副作用。
   - 对“流式中间态”增加小型状态（如 `isGenerating`），UI 由状态驱动显示 stop 按钮、loading 等。

## 第五阶段：功能拓展与进一步优化（在结构稳定后落地）
- 会话内搜索/跳转（按关键字定位消息）。
- 单条消息操作：复制、重新生成、引用回复、长按菜单。
- 更强的导出：按 Markdown/HTML/PDF（后续），“分享给其他应用”。
- 工具链可视化增强：tool 输入/输出的折叠、错误重试提示、耗时显示。
- 多模态增强：多图发送、图片压缩策略、图片消息在会话中可单独保存。

## 编译与验收（每阶段必做）
- 每个阶段完成后执行一次 Debug 编译（assembleDebug），确保无编译错误。
- 关键回归点：
  - 首次启动：无竞态、会话正确加载且 currentSessionId 有效。
  - SSE 流式：不卡顿、不会高频落盘、自动滚动逻辑符合预期。
  - 导出：确实复制到剪贴板且 Snackbar 提示准确。
  - 文件夹：删除后会话正确回到未分组。
  - HTML 预览：内容必定加载且主题适配。
