# MuMuChat（木灵 / MuLing）- 项目体检

最后复核：2026-02-05

## 状态
- 状态标签：active
- 定位：本地 Android AI Chat 应用（Jetpack Compose），支持工具调用、SSE 流式、多模型（OpenAI 兼容端点）。

## 架构速览
- 构建：Gradle（`app/build.gradle`）
- UI：Jetpack Compose
- 网络：OkHttp + okhttp-sse
- 工具生态：web search / browse / news / calculator / file / calendar 等（手工测试清单见 `docs/TOOL_TESTING.md`）

## 风险与建议（优先级）
- 建议补一份“数据存储与隐私”说明（本地保存哪些内容、如何清理、是否加密）。
- 若要更像作品集：补一套“对话回放/可观测”的调试页面或导出机制（便于复盘与排障）。

