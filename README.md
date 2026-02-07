<div align="center">
  <h1>木灵 (MuLing) - Super Agent</h1>

  <p>
    <img src="https://img.shields.io/badge/license-MIT-green" alt="License">
    <img src="https://img.shields.io/badge/platform-Android-blue" alt="Platform">
    <img src="https://img.shields.io/badge/language-Kotlin-purple" alt="Language">
  </p>
  
  <p>
    <strong>
      <a href="MuMuChat-v2.0-beta.apk">📥 Download APK (v2.0 Beta)</a>
    </strong>
  </p>

  <p>
    <a href="#中文">中文</a> | <a href="#english">English</a> | <a href="#日本語">日本語</a>
  </p>
</div>

---

<a name="中文"></a>
## 中文

**木灵 (MuLing)** 是一款基于 **Jetpack Compose** 构建的强大本地 Android AI 聊天应用。它融合了东方美学的水墨宣纸风格，不仅仅是一个聊天机器人，更是一个支持 ReAct 推理模式、多模态交互以及多种工具调用的“超级智能体”。

### ✨ 核心特性

- **ReAct 推理引擎**: 支持任务拆解、思考、行动、观察和迭代的完整推理闭环，解决复杂问题。
- **丰富的工具生态**:
    - **联网搜索**: 集成 **Exa.ai**，实时获取互联网信息。
    - **长期记忆**: 自动记录和检索用户偏好与关键信息，提供个性化服务。
    - **文生图**: 根据文本描述生成高质量图片。
    - **网页浏览**: 抓取并解析网页正文内容。
    - **热搜新闻**: 获取各大平台实时热点。
    - **计算器**: 执行 JavaScript 代码进行精确计算。
- **现代化 UI/UX**:
    - **Markdown & HTML**: 完美支持 Markdown 渲染，并可直接在聊天中预览 HTML/JS 代码效果。
    - **思维可视化**: 透明展示 AI 的思考过程和工具调用细节。
    - **会话管理**: 支持自定义文件夹分类管理对话。
- **高度定制**:
    - **模型兼容**: 支持所有兼容 OpenAI 接口的模型服务商（如 SiliconFlow, DeepSeek 等）。
    - **模型管理**: 支持拉取在线模型列表或手动添加模型。

### 🚀 快速开始

#### 准备工作
- Android Studio Koala 或更高版本。
- JDK 17+。
- **API Key**: 一个兼容 OpenAI 格式的 API 密钥（推荐 SiliconFlow）。
- **Exa API Key**: 用于开启联网搜索功能。

#### 安装步骤
1.  **克隆项目**:
    ```bash
    git clone https://github.com/Xuuuuu04/mumu-chat.git
    ```
2.  在 Android Studio 中打开项目。
3.  同步 Gradle 并运行到您的设备或模拟器。

#### 配置指南
1.  启动应用，打开左侧侧边栏。
2.  点击 **"模型与Key配置"**。
3.  输入您的 **Base URL** (例如 `https://api.siliconflow.cn/v1`)。
4.  输入您的 **API Key** 和 **Exa Search Key**。
5.  点击“拉取更新”获取模型列表，或手动添加您想使用的模型。

### 🛠️ 技术架构
- **MVVM**: 采用标准的 MVVM 架构，`ChatViewModel` 管理状态，UI 由 Compose 驱动。
- **技术栈**: Kotlin, Jetpack Compose, OkHttp (SSE 流式传输), Coil (图片加载), Gson。

### 🤝 参与贡献
欢迎参与贡献！本项目允许二次开发。
1.  Fork 本仓库
2.  创建您的特性分支 (`git checkout -b feature/AmazingFeature`)
3.  提交您的更改 (`git commit -m 'Add some AmazingFeature'`)
4.  推送到分支 (`git push origin feature/AmazingFeature`)
5.  提交 Pull Request

### 📄 开源协议
本项目基于 MIT 协议开源。详情请参阅 `LICENSE` 文件。

---

<a name="english"></a>
## English

**MuLing (木灵)** is a powerful, locally-running Android AI chat application built with **Jetpack Compose**, featuring a refined "Xuan Paper & Ink Wash" aesthetic.

### ✨ Features

- **ReAct Reasoning Engine**: Not just a chatbot, MuMu decomposes complex tasks, reasons, acts, observes, and iterates to solve problems.
- **Rich Tool Ecosystem**:
    - **Web Search**: Real-time information access via **Exa.ai**.
    - **Memory**: Stores and retrieves long-term user facts for personalized interactions.
    - **Image Generation**: Creates images from text descriptions (Text-to-Image).
    - **Web Browsing**: Reads and parses full web pages.
    - **News**: Fetches trending topics from various platforms.
    - **Calculator**: Executes JavaScript code for precise math.
- **Modern UI/UX**:
    - **Markdown & HTML**: Renders rich text and even previews HTML/JS code blocks directly in the chat.
    - **Task Visualization**: Transparently shows the agent's "thinking" process and tool usage.
    - **Chat Management**: Organize conversations into custom folders.
- **Customization**:
    - **Model Agnostic**: Compatible with OpenAI-compatible APIs (SiliconFlow, DeepSeek, etc.).
    - **Model Management**: Fetch, add, and manage your preferred LLMs.

### 🚀 Getting Started

#### Prerequisites
- Android Studio Koala or newer.
- JDK 17+.
- **API Key**: An OpenAI-compatible API key (e.g., from SiliconFlow).
- **Exa API Key**: Required for web search features.

#### Installation
1.  **Clone the repository**:
    ```bash
    git clone https://github.com/Xuuuuu04/mumu-chat.git
    ```
2.  Open the project in Android Studio.
3.  Sync Gradle and run on your device/emulator.

#### Configuration
1.  Launch the app and open the sidebar.
2.  Go to **"Model & Key Configuration"**.
3.  Enter your **Base URL** (e.g., `https://api.siliconflow.cn/v1`).
4.  Enter your **API Key** and **Exa Search Key**.
5.  Fetch or manually add the models you wish to use.

### 🛠️ Architecture
- **MVVM**: Separation of concerns with `ChatViewModel` and Jetpack Compose UI.
- **Tech Stack**: Kotlin, Jetpack Compose, OkHttp (SSE), Coil, Gson.

### 🤝 Contributing
Contributions are welcome! This project is open for secondary development.
1.  Fork the Project
2.  Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3.  Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4.  Push to the Branch (`git push origin feature/AmazingFeature`)
5.  Open a Pull Request

### 📄 License
Distributed under the MIT License. See `LICENSE` for more information.

---

<a name="日本語"></a>
## 日本語

**MuMu Chat** は、**Jetpack Compose** で構築された強力な Android AI チャットアプリです。ReAct 推論、マルチモーダル（テキスト、画像）、および Web 検索、メモリ管理、コード実行などの多様なツール統合をサポートする「スーパーエージェント」として設計されています。

### ✨ 主な機能

- **ReAct 推論エンジン**: 複雑なタスクを分解し、思考、行動、観察、反復を行うことで問題を解決します。
- **豊富なツールエコシステム**:
    - **Web 検索**: **Exa.ai** と連携し、リアルタイムの情報にアクセス。
    - **メモリ管理**: ユーザーの長期的な情報を保存・検索し、パーソナライズされた対話を実現。
    - **画像生成**: テキストの説明から画像を生成（Text-to-Image）。
    - **Web ブラウジング**: Web ページの全文を取得して解析。
    - **ニュース**: 様々なプラットフォームからトレンド情報を取得。
    - **計算機**: JavaScript コードを実行して正確な計算を行います。
- **モダンな UI/UX**:
    - **Markdown & HTML**: リッチテキストのレンダリングに加え、HTML/JS コードブロックをチャット内で直接プレビュー可能。
    - **タスク可視化**: エージェントの思考プロセスとツール使用状況を透明化。
    - **チャット管理**: 会話をカスタムフォルダで整理。
- **カスタマイズ**:
    - **モデル互換性**: OpenAI 互換 API（SiliconFlow, DeepSeek など）に対応。
    - **モデル管理**: API からモデルリストを取得、または手動で追加・管理可能。

### 🚀 始め方

#### 前提条件
- Android Studio Koala 以降。
- JDK 17+。
- **API Key**: OpenAI 互換の API キー（SiliconFlow など）。
- **Exa API Key**: Web 検索機能に必要です。

#### インストール
1.  **リポジトリをクローン**:
    ```bash
    git clone https://github.com/Xuuuuu04/mumu-chat.git
    ```
2.  Android Studio でプロジェクトを開きます。
3.  Gradle を同期し、デバイスまたはエミュレータで実行します。

#### 設定
1.  アプリを起動し、サイドバーを開きます。
2.  **"模型与Key配置" (モデルとキー設定)** をクリックします。
3.  **Base URL** を入力します（例: `https://api.siliconflow.cn/v1`）。
4.  **API Key** と **Exa Search Key** を入力します。
5.  モデルリストを更新するか、使用したいモデルを手動で追加します。

### 🛠️ アーキテクチャ
- **MVVM**: `ChatViewModel` と Jetpack Compose UI による関心の分離。
- **技術スタック**: Kotlin, Jetpack Compose, OkHttp (SSE), Coil, Gson.

### 🤝 貢献について
貢献は大歓迎です！このプロジェクトは二次開発が許可されています。
1.  プロジェクトを Fork する
2.  機能ブランチを作成 (`git checkout -b feature/AmazingFeature`)
3.  変更をコミット (`git commit -m 'Add some AmazingFeature'`)
4.  ブランチにプッシュ (`git push origin feature/AmazingFeature`)
5.  Pull Request を作成

### 📄 ライセンス
MIT ライセンスの下で配布されています。詳細は `LICENSE` ファイルをご覧ください。

## 开发进度（截至 2026-02-07）
- 已完成可公开仓库基线整理：补齐许可证、清理敏感与内部说明文件。
- 当前版本可构建/可运行，后续迭代以 issue 与提交记录持续公开追踪。

## Language
- 中文：[`README.md`](./README.md)
- English：[`README_EN.md`](./README_EN.md)

## 统一源码目录
- 源码入口：[`src/`](./src)

## 目录结构
- 结构说明：[`docs/PROJECT_STRUCTURE.md`](./docs/PROJECT_STRUCTURE.md)

## 迁移说明
- 核心目录已迁移到 `src/` 下。
- 根目录保留兼容软链接，历史命令与路径可继续使用。
