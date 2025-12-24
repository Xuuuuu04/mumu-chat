package mumu.xsy.mumuchat.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
 * 动态变化的品牌渐变背景，带粒子效果
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

    // 粒子动画
    val particleAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "particle_alpha"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        // 基础背景色
        drawRect(color = if (isDark) DarkBgBase else LightBgBase)

        // 品牌主色光晕
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BrandPrimary.copy(alpha = if (isDark) 0.15f else 0.08f),
                    BrandPrimary.copy(alpha = 0.05f),
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
                    BrandSecondary.copy(alpha = 0.04f),
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

        // Accent 装饰光晕
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BrandAccent.copy(alpha = if (isDark) 0.08f else 0.04f),
                    Color.Transparent
                ),
                center = Offset(
                    size.width * 0.5f,
                    size.height * 0.5f
                ),
                radius = size.width * 0.4f
            ),
            radius = size.width * 0.4f
        )

        // 装饰性粒子
        val particlePositions = listOf(
            Offset(size.width * 0.15f, size.height * 0.3f),
            Offset(size.width * 0.85f, size.height * 0.2f),
            Offset(size.width * 0.7f, size.height * 0.85f),
            Offset(size.width * 0.3f, size.height * 0.75f),
        )

        particlePositions.forEachIndexed { index, pos ->
            val offsetX = if (index % 2 == 0) animOffset * 0.5f else 0f
            val offsetY = if (index % 2 == 1) animOffset * 0.5f else 0f
            drawCircle(
                color = BrandPrimary.copy(alpha = particleAlpha * 0.3f),
                radius = 4f,
                center = Offset(pos.x + offsetX, pos.y + offsetY)
            )
        }
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

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Canvas(modifier = Modifier.size(size * pulseScale)) {
        val center = Offset(size.toPx() / 2, size.toPx() / 2)
        val radius = size.toPx() / 2

        // 外发光效果
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BrandPrimary.copy(0.2f),
                    BrandPrimary.copy(0.05f),
                    Color.Transparent
                ),
                center = center,
                radius = radius * 1.5f
            ),
            radius = radius * 1.5f
        )

        // 径向渐变光晕
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BrandPrimary.copy(0.4f),
                    BrandPrimary.copy(0.2f),
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

        // 内边框
        val innerPath = Path().apply {
            val segments = 6
            val r = radius * 0.55f
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

        drawPath(
            path = innerPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(0.3f),
                    Color.White.copy(0.1f)
                ),
                start = Offset.Zero,
                end = Offset(size.toPx(), size.toPx())
            ),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
        )

        // 旋转的光点
        if (isAnimating) {
            rotate(rotation, pivot = center) {
                drawCircle(
                    color = Color.White.copy(0.4f),
                    radius = radius * 0.12f,
                    center = Offset(center.x + radius * 0.4f, center.y)
                )
            }
        }
    }
}
