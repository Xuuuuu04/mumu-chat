package mumu.xsy.mumuchat.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
 * 木灵 Logo 组件 - 印章风格
 * 模拟传统朱砂印章
 */
@Composable
fun MuLingLogo(
    size: Dp,
    isAnimating: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(
                color = Color(0xFFB12828), // 还原印章红
                shape = RoundedCornerShape(size * 0.2f)
            ),
        contentAlignment = Alignment.Center
    ) {
        // 使用 Canvas 绘制“木”字
        Canvas(modifier = Modifier.fillMaxSize(0.6f)) {
            val w = size.toPx() * 0.6f
            val h = size.toPx() * 0.6f
            val stroke = w * 0.12f

            // 竖
            drawLine(
                color = Color.White,
                start = Offset(w / 2, 0f),
                end = Offset(w / 2, h),
                strokeWidth = stroke,
                cap = StrokeCap.Butt
            )
            // 横
            drawLine(
                color = Color.White,
                start = Offset(0f, h * 0.35f),
                end = Offset(w, h * 0.35f),
                strokeWidth = stroke,
                cap = StrokeCap.Butt
            )
            // 撇
            drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2, h * 0.35f)
                    quadraticTo(w * 0.4f, h * 0.6f, 0f, h * 0.95f)
                },
                color = Color.White,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            // 捺
            drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2, h * 0.35f)
                    quadraticTo(w * 0.6f, h * 0.6f, w, h * 0.95f)
                },
                color = Color.White,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Butt)
            )
        }
    }
}
