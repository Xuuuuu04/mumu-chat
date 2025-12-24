package mumu.xsy.mumuchat.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mumu.xsy.mumuchat.ChatStep
import mumu.xsy.mumuchat.StepType
import mumu.xsy.mumuchat.ui.theme.*

/**
 * 任务流程容器组件
 * 显示 AI 思考过程和工具调用步骤
 */
@Composable
fun TaskFlowContainer(steps: List<ChatStep>) {
    var allExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // 头部 - 可折叠
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { allExpanded = !allExpanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    null,
                    tint = BrandAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "任务链执行中",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = BrandAccent
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (allExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 可折叠的内容区域
            AnimatedVisibility(visible = allExpanded) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .heightIn(max = 240.dp)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(steps) { _, step ->
                            StepItem(step = step)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个步骤项组件
 */
@Composable
fun StepItem(step: ChatStep) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 根据步骤类型显示不同的图标和动画
            val alpha by if (!step.isFinished) {
                rememberInfiniteTransition(label = "step_alpha")
                    .animateFloat(0.4f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse))
            } else {
                remember { mutableFloatStateOf(1f) }
            }

            Icon(
                if (step.type == StepType.THINKING) Icons.Default.Face else Icons.Default.Settings,
                null,
                tint = if (step.type == StepType.THINKING) BrandSecondary else ThinkingAccent,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { this.alpha = alpha }
            )

            Spacer(Modifier.width(8.dp))

            Text(
                if (step.type == StepType.THINKING) "思考中..." else "Action: ${step.toolName}",
                style = MaterialTheme.typography.labelSmall,
                color = if (step.type == StepType.THINKING) BrandSecondary else ThinkingAccent
            )

            Spacer(Modifier.weight(1f))

            // 如果有内容，显示展开/收起图标
            if (step.content.isNotBlank()) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.Add,
                    null,
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 步骤详情 - 可展开
        AnimatedVisibility(visible = expanded && step.content.isNotBlank()) {
            Text(
                text = step.content,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.8f)
                ),
                modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 8.dp)
            )
        }
    }
}
