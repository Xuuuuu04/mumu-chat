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
 * 宣纸质感背景组件
 * 模拟传统宣纸的纤维质感与光泽
 */
@Composable
fun XuanPaperBackground(isDark: Boolean) {
    val baseColor = if (isDark) DarkBgBase else LightBgBase
    val fiberColor = if (isDark) Color.White.copy(alpha = 0.03f) else Color(0xFFE5E0D5).copy(alpha = 0.4f)
    val inkWashColor = if (isDark) Color(0xFF1A1A1A).copy(alpha = 0.3f) else Color(0xFFDCDCDC).copy(alpha = 0.1f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        // 1. 基础纸张色
        drawRect(color = baseColor)

        // 2. 模拟墨晕效果 (Ink Wash)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(inkWashColor, Color.Transparent),
                center = Offset(size.width * 0.3f, size.height * 0.2f),
                radius = size.width * 0.8f
            ),
            radius = size.width * 0.8f
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(inkWashColor, Color.Transparent),
                center = Offset(size.width * 0.7f, size.height * 0.8f),
                radius = size.width * 0.6f
            ),
            radius = size.width * 0.6f
        )

        // 3. 模拟宣纸纤维 (Fibers)
        // 使用随机线条模拟纤维质感
        val seed = 42
        val random = java.util.Random(seed.toLong())
        for (i in 0 until 150) {
            val startX = random.nextFloat() * size.width
            val startY = random.nextFloat() * size.height
            val length = 5f + random.nextFloat() * 15f
            val angle = random.nextFloat() * Math.PI.toFloat() * 2
            
            val endX = startX + length * cos(angle)
            val endY = startY + length * sin(angle)
            
            drawLine(
                color = fiberColor,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 0.5f,
                cap = StrokeCap.Round
            )
        }
        
        // 4. 边缘暗角 (Vignette)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.05f)),
                center = center,
                radius = size.maxDimension / 1.5f
            )
        )
    }
}

/**
 * MuMu Logo 组件 - 印章风格
 * 模拟传统朱砂印章
 */
@Composable
fun MuMuLogo(
    size: Dp,
    isAnimating: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .size(size * pulseScale)
            .background(SealRed, RoundedCornerShape(4.dp))
            .padding(size * 0.15f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.toPx() / 2, size.toPx() / 2)
            
            // 绘制白色镂空的“木”字意象（极简线条）
            val strokeWidth = 2.dp.toPx()
            val color = Color.White
            
            // 垂直中轴
            drawLine(
                color = color,
                start = Offset(size.toPx() * 0.5f, size.toPx() * 0.1f),
                end = Offset(size.toPx() * 0.5f, size.toPx() * 0.9f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
            
            // 横梁
            drawLine(
                color = color,
                start = Offset(size.toPx() * 0.2f, size.toPx() * 0.4f),
                end = Offset(size.toPx() * 0.8f, size.toPx() * 0.4f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
            
            // 左撇
            drawLine(
                color = color,
                start = Offset(size.toPx() * 0.5f, size.toPx() * 0.4f),
                end = Offset(size.toPx() * 0.2f, size.toPx() * 0.8f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
            
            // 右捺
            drawLine(
                color = color,
                start = Offset(size.toPx() * 0.5f, size.toPx() * 0.4f),
                end = Offset(size.toPx() * 0.8f, size.toPx() * 0.8f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square
            )
        }
    }
}
