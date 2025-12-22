package mumu.xsy.mumuchat.ui.theme

import androidx.compose.ui.graphics.Color

// === 核心品牌色 (高级感) ===
val BrandPrimary = Color(0xFF4F46E5) // 靛蓝
val BrandSecondary = Color(0xFF7C3AED) // 紫罗兰
val BrandAccent = Color(0xFF06B6D4) // 霓虹青

// === 全局背景层级 (深色 - Deep Space) ===
val DarkBgBase = Color(0xFF0B0C10) // 极黑底
val DarkBgGradientTop = Color(0xFF111318)
val DarkBgGradientBottom = Color(0xFF0F172A)
val DarkSurface = Color(0xFF1E293B).copy(alpha = 0.7f) // 玻璃质感表面
val DarkBorder = Color(0xFFFFFFFF).copy(alpha = 0.1f) // 极细边框

// === 全局背景层级 (浅色 - Ceramic) ===
val LightBgBase = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF).copy(alpha = 0.8f)
val LightBorder = Color(0xFF000000).copy(alpha = 0.05f)

// === 消息气泡专用 ===
val UserBubbleDark = Color(0xFF312E81)
val UserBubbleLight = Color(0xFFE0E7FF)
val ThinkingProcessBg = Color(0xFF10B981).copy(alpha = 0.08f)
val ThinkingAccent = Color(0xFF10B981)

// === 文字颜色 ===
val TextPrimaryDark = Color(0xFFF1F5F9)
val TextSecondaryDark = Color(0xFF94A3B8)
val TextPrimaryLight = Color(0xFF0F172A)
val TextSecondaryLight = Color(0xFF64748B)

// === 渐变色定义 (用于 Logo) ===
val LogoGradientStart = Color(0xFF4F46E5)
val LogoGradientEnd = Color(0xFFDB2777)