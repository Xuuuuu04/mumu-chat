package mumu.xsy.mumuchat

/**
 * 应用常量定义
 * 统一管理 magic numbers 和配置常量
 */
object Constants {

    // ==================== 网络配置 ====================
    object Network {
        const val CONNECT_TIMEOUT_SECONDS = 60L
        const val READ_TIMEOUT_SECONDS = 60L
        const val DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1"
    }

    // ==================== AI 模型配置 ====================
    object AI {
        const val DEFAULT_MODEL = "MiniMaxAI/MiniMax-M2"
        const val TITLE_GENERATION_MODEL = "deepseek-ai/DeepSeek-V3"
        const val IMAGE_GENERATION_MODEL = "black-forest-labs/FLUX.1-schnell"

        // 视觉模型识别关键词
        const val VISION_MODEL_KEYWORDS = "vl,gemini,vision,omni,glm,step,ocr"

        // 默认文件夹
        val DEFAULT_FOLDERS = listOf("生活", "工作", "学习")
    }

    // ==================== 图片生成配置 ====================
    object Image {
        const val DEFAULT_IMAGE_SIZE = "1024x1024"
        const val DEFAULT_INFERENCE_STEPS = 4
        const val IMAGE_QUALITY = 80
        const val MAX_PREVIEW_SIZE = 260
    }

    // ==================== 文本处理 ====================
    object Text {
        const val MAX_HTML_PREVIEW_LENGTH = 8000
        const val TRUNCATION_SUFFIX = "..."
        const val MAX_TITLE_LENGTH = 5
        const val MIN_TITLE_LENGTH = 2
    }

    // ==================== 缓存配置 ====================
    object Cache {
        const val IMAGE_CACHE_PREFIX = "img_"
        const val IMAGE_CACHE_DIR = "image_cache"
    }

    // ==================== UI 尺寸配置 ====================
    object UI {
        const val CORNER_RADIUS_LARGE = 24
        const val CORNER_RADIUS_MEDIUM = 16
        const val CORNER_RADIUS_SMALL = 12
        const val CORNER_RADIUS_TINY = 8

        const val SPACING_LARGE = 32
        const val SPACING_MEDIUM = 16
        const val SPACING_SMALL = 8
        const val SPACING_TINY = 4

        const val ICON_SIZE_LARGE = 44
        const val ICON_SIZE_MEDIUM = 32
        const val ICON_SIZE_SMALL = 24
        const val ICON_SIZE_TINY = 16

        const val BUTTON_HEIGHT = 52
        const val INPUT_HEIGHT = 44
        const val MESSAGE_MAX_WIDTH = 300
        const val SIDEBAR_WIDTH = 320
    }

    // ==================== 动画配置 ====================
    object Animation {
        const val LOGO_ROTATION_DURATION_MS = 12000
        const val MESH_ANIMATION_DURATION_MS = 10000
        const val THINKING_BLINK_DURATION_MS = 800
    }

    // ==================== 日志标签 ====================
    object Logs {
        const val TAG = "ChatViewModel"
    }

    object Notifications {
        const val CHANNEL_STATUS = "mm_status"
        const val CHANNEL_REMINDER = "mm_reminder"
        const val EXTRA_APP_ACTION = "mm_app_action"
        const val ACTION_STOP_GENERATION = "stop_generation"
        const val ACTION_ID_GENERATION = "generation"
        const val ACTION_ID_BROWSE = "browse"
        const val ACTION_ID_FILE = "file"
        const val KEEPALIVE_NOTIFICATION_ID = 1001
    }
}
