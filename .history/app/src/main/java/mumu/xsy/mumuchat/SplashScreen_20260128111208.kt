package mumu.xsy.mumuchat

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import mumu.xsy.mumuchat.ui.components.MuLingLogo
import mumu.xsy.mumuchat.ui.theme.DarkBgBase
import mumu.xsy.mumuchat.ui.theme.LightBgBase

@Composable
fun SplashScreen(onAnimationFinished: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    var didRequest by remember { mutableStateOf(false) }
    var skip by remember { mutableStateOf(false) }
    var requestNonce by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        requestNonce += 1
    }
    
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
        delay(2500)
        if (skip) onAnimationFinished()
    }

    LaunchedEffect(didRequest) {
        if (didRequest) return@LaunchedEffect
        didRequest = true
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        perms.add(Manifest.permission.READ_CALENDAR)
        perms.add(Manifest.permission.WRITE_CALENDAR)
        permissionLauncher.launch(perms.toTypedArray())

        if (Build.VERSION.SDK_INT >= 31) {
            val am = context.getSystemService(AlarmManager::class.java)
            if (am != null && !am.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        }

        if (Build.VERSION.SDK_INT >= 23) {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            }
        }
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
                MuLingLogo(size = 100.dp, isAnimating = true)
            }
            
            Spacer(Modifier.height(48.dp))
            
            // 底部品牌文字
            Text(
                text = "木灵",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alpha(alpha)
            )
            
            Text(
                text = "笔墨之间，见微知著",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.5f),
                modifier = Modifier.padding(top = 12.dp).alpha(alpha)
            )

            Spacer(Modifier.height(20.dp))

            if (!skip) {
                val notificationsOk = remember(requestNonce) {
                    if (Build.VERSION.SDK_INT < 33) true
                    else context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                val calendarOk = remember(requestNonce) {
                    context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                val exactAlarmOk = remember(requestNonce) {
                    if (Build.VERSION.SDK_INT < 31) true
                    else context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
                }
                val batteryOk = remember(requestNonce) {
                    if (Build.VERSION.SDK_INT < 23) true
                    else context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("系统权限", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("通知：${if (notificationsOk) "已开启" else "未开启"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("日历：${if (calendarOk) "已开启" else "未开启"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("定时提醒：${if (exactAlarmOk) "可精确" else "可能不精确"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("电池优化：${if (batteryOk) "已忽略" else "可能被限制"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { skip = true; onAnimationFinished() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f)
                            ) { Text("进入应用") }
                            OutlinedButton(
                                onClick = { didRequest = false; requestNonce += 1 },
                                modifier = Modifier.weight(1f)
                            ) { Text("重试授权") }
                        }
                    }
                }
            }
        }
    }
}
