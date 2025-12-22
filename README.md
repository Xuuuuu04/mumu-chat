# MuMu Chat - Super Agent

MuMu Chat is a powerful, locally-running Android AI chat application built with Jetpack Compose. It supports ReAct-style reasoning, tool calling (web search, memory management, image generation), and customizable model endpoints.

## Features

- **ReAct Reasoning Loop**: Decomposes complex tasks, thinks, acts, observes, and iterates.
- **Tool Support**:
    - **Web Search**: Integration with Exa.ai for real-time information.
    - **Memory Management**: Long-term memory storage (save/retrieve user facts).
    - **Image Generation**: Text-to-image capabilities using FLUX.1 via API.
    - **Web Browsing**: Fetches and parses web page content.
    - **Calculator**: Executes JavaScript for precise calculations.
    - **News Boards**: Fetches trending news from various platforms.
- **Rich UI**:
    - **Markdown Support**: Renders Markdown with syntax highlighting.
    - **HTML Preview**: Detects and renders HTML blocks with a live preview.
    - **Image Upload**: Upload images for multimodal models.
    - **Task Flow Visualization**: See the agent's thinking process and tool calls in real-time.
- **Customization**:
    - **Model Management**: Add/Remove models, fetch from API.
    - **Folder Management**: Organize chats into custom folders.
    - **System Prompt**: Customize the agent's persona and instructions.

## Getting Started

### Prerequisites

- Android Studio Koala or newer.
- JDK 17+.
- An API Key for a compatible LLM provider (e.g., SiliconFlow, DeepSeek, OpenAI-compatible).
- An Exa Search API Key (for web search capabilities).

### Installation

1.  Clone the repository:
    ```bash
    git clone git@gitcode.com:mumu_xsy/mumuchat.git
    ```
2.  Open the project in Android Studio.
3.  Sync Gradle project.
4.  Run on an Android device or emulator.

### Configuration

1.  Open the app sidebar.
2.  Click on **"Model & Key Configuration"**.
3.  Enter your **Base URL** (e.g., `https://api.siliconflow.cn/v1`).
4.  Enter your **API Key**.
5.  Enter your **Exa Search Key**.
6.  Manage your models:
    - Click **"Fetch Updates"** to retrieve models from the provider.
    - Manually add model names if needed.
    - Delete unused models.

## Architecture

- **MVVM Pattern**: `ChatViewModel` manages state and logic, `ChatScreen` handles UI.
- **Jetpack Compose**: Modern, declarative UI toolkit.
- **OkHttp**: Network requests and Server-Sent Events (SSE) for streaming responses.
- **Coil**: Image loading.
- **Gson**: JSON parsing.

## License

MIT
