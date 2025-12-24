package mumu.xsy.mumuchat.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mumu.xsy.mumuchat.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mesh 渐变背景组件
 * 动态变化的品牌渐变背景
 */
@Composable
fun MeshBackground(isDark: Boolean) {
    val transition = rememberInfiniteTransition(label = "mesh")
    val animOffset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        // 基础背景色
        drawRect(color = if (isDark) DarkBgBase else LightBgBase)

        // 品牌主色光晕
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BrandPrimary.copy(alpha = if (isDark) 0.15f else 0.08f),
                    Color.Transparent
                ),
                center = Offset(
                    size.width * 0.2f + animOffset,
                    size.height * 0.2f
                ),
                radius = size.width * 0.8f
            ),
            radius = size.width * 0.8f
        )

        // 品牌辅助色光晕
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BrandSecondary.copy(alpha = if (isDark) 0.12f else 0.06f),
                    Color.Transparent
                ),
                center = Offset(
                    size.width * 0.8f - animOffset,
                    size.height * 0.7f
                ),
                radius = size.width * 0.7f
            ),
            radius = size.width * 0.7f
        )
    }
}

/**
 * MuMu Logo 组件
 * 带旋转动画的品牌图标
 */
@Composable
fun MuMuLogo(
    size: Dp,
    isAnimating: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rot"
    )

    Canvas(modifier = Modifier.size(size)) {
        val center = Offset(size.toPx() / 2, size.toPx() / 2)
        val radius = size.toPx() / 2

        // 径向渐变光晕
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BrandPrimary.copy(0.4f),
                    Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius
        )

        // 六边形路径
        val path = Path().apply {
            val segments = 6
            val r = radius * 0.7f
            for (i in 0 until segments) {
                val angle = i * (Math.PI * 2 / segments) - (Math.PI / 2)
                val x = center.x + r * cos(angle).toFloat()
                val y = center.y + r * sin(angle).toFloat()
                if (i == 0) {
                    moveTo(x, y)
                } else {
                    lineTo(x, y)
                }
            }
            close()
        }

        // 渐变填充
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(LogoGradientStart, LogoGradientEnd),
                start = Offset.Zero,
                end = Offset(size.toPx(), size.toPx())
            )
        )

        // 旋转的光点
        if (isAnimating) {
            rotate(rotation, pivot = center) {
                drawCircle(
                    color = Color.White.copy(0.3f),
                    radius = radius * 0.15f,
                    center = Offset(center.x + radius * 0.4f, center.y)
                )
            }
        }
    }
}
