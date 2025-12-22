package mumu.xsy.mumuchat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import mumu.xsy.mumuchat.ui.theme.DarkBgBase

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import mumu.xsy.mumuchat.ui.theme.DarkBgBase
import mumu.xsy.mumuchat.ui.theme.LightBgBase

@Composable
fun SplashScreen(onAnimationFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    
    // 1. Logo 缩放动画 (Blooming)
    val scale by animateFloatAsState(
        targetValue = if (startAnimation) 1.2f else 0.5f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )
    
    // 2. 文字渐显动画
    val alpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(1200, easing = LinearOutSlowInEasing),
        label = "alpha"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(2500) // 动画持续时长
        onAnimationFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) DarkBgBase else LightBgBase),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 中心绽放的 Logo
            Box(modifier = Modifier.scale(scale)) {
                MuMuLogo(size = 120.dp, isAnimating = true)
            }
            
            Spacer(Modifier.height(48.dp))
            
            // 底部品牌文字
            Text(
                text = "MuMu Intelligence",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Light,
                    letterSpacing = 4.sp
                ),
                color = if (isDark) Color.White else Color(0xFF0F172A),
                modifier = Modifier.alpha(alpha)
            )
            
            Text(
                text = "Everything starts with a thought",
                style = MaterialTheme.typography.labelSmall,
                color = (if (isDark) Color.White else Color(0xFF0F172A)).copy(alpha = alpha * 0.5f),
                modifier = Modifier.padding(top = 8.dp).alpha(alpha)
            )
        }
    }
}
