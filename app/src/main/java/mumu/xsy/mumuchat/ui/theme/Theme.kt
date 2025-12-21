package mumu.xsy.mumuchat.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BrandPrimary,
    background = DarkBackground,
    surface = DarkBackground, // 让背景统一，使用 Surface 组件做层级
    surfaceVariant = DarkSurface,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    secondary = DarkTextSecondary,
    tertiary = ThinkingAccent
)

private val LightColorScheme = lightColorScheme(
    primary = BrandPrimary,
    background = LightBackground,
    surface = LightBackground,
    surfaceVariant = LightSurface,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    secondary = LightTextSecondary,
    tertiary = ThinkingAccent
)

@Composable
fun MuMuChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography, // 确保 Typography.kt 存在，或者使用默认
        content = content
    )
}