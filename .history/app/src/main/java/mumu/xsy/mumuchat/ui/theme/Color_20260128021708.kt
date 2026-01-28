package mumu.xsy.mumuchat.ui.theme

import androidx.compose.ui.graphics.Color

// === 核心品牌色 (水墨雅韵) ===
val BrandPrimary = Color(0xFF1A1A1A) // 浓墨
val BrandSecondary = Color(0xFF2F4F4F) // 黛青
val BrandAccent = Color(0xFFB22222) // 朱砂

// === 全局背景层级 (宣纸 - Xuan Paper) ===
val LightBgBase = Color(0xFFFDFCF8) // 古法宣纸色
val LightSurface = Color(0xFFFFFFFF).copy(alpha = 0.6f)
val LightBorder = Color(0xFF1A1A1A).copy(alpha = 0.08f)

// === 全局背景层级 (玄色 - Deep Ink) ===
val DarkBgBase = Color(0xFF0D0D0D) // 玄色底
val DarkSurface = Color(0xFF1A1A1A).copy(alpha = 0.7f)
val DarkBorder = Color(0xFFFFFFFF).copy(alpha = 0.08f)

// === 消息气泡专用 (无 Emoji，纯粹质感) ===
val UserBubbleDark = Color(0xFF1E293B)
val UserBubbleLight = Color(0xFFE5E7EB)
val ThinkingProcessBg = Color(0xFF2F4F4F).copy(alpha = 0.05f)
val ThinkingAccent = Color(0xFF2F4F4F)

// === 文字颜色 (书法质感) ===
val TextPrimaryLight = Color(0xFF1A1A1A)
val TextSecondaryLight = Color(0xFF555555)
val TextPrimaryDark = Color(0xFFE0E0E0)
val TextSecondaryDark = Color(0xFF999999)

// === 动态品牌色 (适配暗色模式) ===
val BrandPrimaryDark = Color(0xFFF0F0F0) // 暗色模式下的“浅墨”或“银钩”

// === 印章与装饰 ===
val SealRed = Color(0xFFB22222)
val InkWashGrey = Color(0xFF888888)