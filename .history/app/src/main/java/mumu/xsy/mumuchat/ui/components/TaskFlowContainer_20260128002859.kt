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
                    Icons.Default.HistoryEdu,
                    null,
                    tint = BrandAccent.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "推演与调用记录",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = BrandAccent.copy(alpha = 0.8f)
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
    val durationMs = remember(step.startedAt, step.finishedAt) {
        val start = step.startedAt
        val end = step.finishedAt
        if (start != null && end != null && end >= start) end - start else null
    }

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
                    .animateFloat(0.3f, 0.7f, infiniteRepeatable(tween(1200), RepeatMode.Reverse))
            } else {
                remember { mutableFloatStateOf(1f) }
            }

            Icon(
                if (step.type == StepType.THINKING) Icons.Default.Brush else Icons.Default.Settings,
                null,
                tint = if (step.error != null) MaterialTheme.colorScheme.error else if (step.type == StepType.THINKING) BrandSecondary else ThinkingAccent,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { this.alpha = alpha }
            )

            Spacer(Modifier.width(8.dp))

            Text(
                if (step.type == StepType.THINKING) "笔耕不辍，正在深度推演" else "调用工具: ${step.toolName}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                ),
                color = if (step.error != null) MaterialTheme.colorScheme.error else if (step.type == StepType.THINKING) BrandSecondary else ThinkingAccent
            )

            Spacer(Modifier.weight(1f))

            // 如果有内容，显示展开/收起图标
            if (durationMs != null && step.isFinished) {
                Text(
                    text = "${durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            val hasDetails = step.content.isNotBlank() || step.input?.isNotBlank() == true || step.output?.isNotBlank() == true || step.error?.isNotBlank() == true
            if (hasDetails) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.Add,
                    null,
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 步骤详情 - 可展开
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 8.dp)) {
                if (step.type == StepType.THINKING) {
                    if (step.content.isNotBlank()) {
                        Text(
                            text = step.content,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.8f)
                            )
                        )
                    }
                } else {
                    if (!step.input.isNullOrBlank()) {
                        Text("Input", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = step.input,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.85f)
                                ),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    val outputText = step.error ?: step.output
                    if (!outputText.isNullOrBlank()) {
                        Text(
                            if (step.error != null) "Error" else "Output",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (step.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = (if (step.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface).copy(alpha = 0.25f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = outputText,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = (if (step.error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.9f)
                                ),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
