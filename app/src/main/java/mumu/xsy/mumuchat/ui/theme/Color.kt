package mumu.xsy.mumuchat.ui.theme

import androidx.compose.ui.graphics.Color

// === 品牌核心 (Liquid Intelligence) ===
val BrandPrimary = Color(0xFF6366F1) // Indigo 500
val BrandSecondary = Color(0xFFEC4899) // Pink 500
val BrandTertiary = Color(0xFF06B6D4) // Cyan 500

// === 浅色模式 (Ceramic / Paper) ===
val LightBackground = Color(0xFFFAFAFA) // 陶瓷白
val LightSurface = Color(0xFFFFFFFF) // 纯白
val LightSurfaceVariant = Color(0xFFF3F4F6) // 极浅灰
val LightTextPrimary = Color(0xFF111827) // 墨黑
val LightTextSecondary = Color(0xFF6B7280) // 石墨灰
val LightBubbleUser = Color(0xFFEEF2FF) // 极淡的靛蓝背景
val LightBubbleAiBg = Color.Transparent // AI 无背景

// === 深色模式 (Deep Space) ===
val DarkBackground = Color(0xFF0F111A) // 深空蓝黑 (非纯黑)
val DarkSurface = Color(0xFF1E293B) // 深蓝灰
val DarkSurfaceVariant = Color(0xFF334155) // 亮一点的蓝灰
val DarkTextPrimary = Color(0xFFF9FAFB) // 星光白
val DarkTextSecondary = Color(0xFF9CA3AF) // 月尘灰
val DarkBubbleUser = Color(0xFF312E81) // 深靛蓝

// === 辅助色 ===
val ThinkingProcessBgLight = Color(0xFFF0FDF4) // 浅绿 (Claude 风格)
val ThinkingProcessBgDark = Color(0xFF14532D).copy(alpha = 0.4f) // 深绿
val ThinkingAccent = Color(0xFF10B981) // 翡翠绿

// === 渐变色定义 ===
val LogoGradientStart = Color(0xFF4F46E5)
val LogoGradientMid = Color(0xFF9333EA)
val LogoGradientEnd = Color(0xFFDB2777)