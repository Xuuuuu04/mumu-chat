package mumu.xsy.mumuchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    background = DarkBgBase,
    surface = DarkSurface,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = DarkBgGradientTop,
    onSurfaceVariant = TextSecondaryDark,
    secondary = BrandSecondary,
    tertiary = BrandAccent,
    outline = DarkBorder
)

private val LightColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    background = LightBgBase,
    surface = LightSurface,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color.White,
    onSurfaceVariant = TextSecondaryLight,
    secondary = BrandSecondary,
    tertiary = BrandAccent,
    outline = LightBorder
)

@Composable
fun MuMuChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
