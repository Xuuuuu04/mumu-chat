package mumu.xsy.mumuchat.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import mumu.xsy.mumuchat.ChatMessage
import mumu.xsy.mumuchat.ChatViewModel
import mumu.xsy.mumuchat.MessageRole
import mumu.xsy.mumuchat.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 消息区域组件
 * 显示聊天消息列表，自动滚动到底部
 */
@Composable
fun ChatMessagesArea(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    onPreviewHtml: (String) -> Unit
) {
    val listState = rememberLazyListState()

    // 当消息数量变化时，滚动到底部
    LaunchedEffect(viewModel.currentMessages.size) {
        if (viewModel.currentMessages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.currentMessages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        itemsIndexed(viewModel.currentMessages) { index, message ->
            MessageItem(
                message = message,
                onEdit = { viewModel.editMessage(index) },
                onPreviewHtml = onPreviewHtml
            )
        }
    }
}

/**
 * 单条消息组件
 * 根据角色显示不同的消息气泡
 */
@Composable
fun MessageItem(
    message: ChatMessage,
    onEdit: () -> Unit,
    onPreviewHtml: (String) -> Unit
) {
    val isUser = message.role == MessageRole.USER
    val isDark = isSystemInDarkTheme()

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // AI 消息显示发送者信息
        if (!isUser) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                MuMuLogo(size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "MuMu Intelligence",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isUser) {
            UserBubble(message = message, isDark = isDark, onEdit = onEdit)
        } else {
            AiBubble(message = message, isDark = isDark, onPreviewHtml = onPreviewHtml)
        }
    }
}

/**
 * 用户消息气泡组件
 */
@Composable
fun UserBubble(
    message: ChatMessage,
    isDark: Boolean,
    onEdit: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    Column(horizontalAlignment = Alignment.End) {
        // 图片附件
        if (message.imageUrl != null) {
            AsyncImage(
                model = message.imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .sizeIn(maxWidth = 260.dp, maxHeight = 260.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(20.dp)
                    ),
                contentScale = ContentScale.Crop
            )
        }

        Surface(
            color = if (isDark) UserBubbleDark else UserBubbleLight,
            shape = RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        null,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onEdit() },
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                    )
                    Icon(
                        Icons.Default.Share,
                        null,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { clipboard.setText(AnnotatedString(message.content)) },
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                    )
                }
            }
        }
    }
}

/**
 * AI 消息气泡组件
 * 包含 Markdown 渲染、HTML 预览和任务链显示
 */
@Composable
fun AiBubble(
    message: ChatMessage,
    isDark: Boolean,
    onPreviewHtml: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current

    // 检测 HTML 代码块
    val htmlBlock = remember(message.content) {
        "```html\\s+(.*?)\\s+```".toRegex(RegexOption.DOT_MATCHES_ALL)
            .find(message.content)?.groupValues?.get(1)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 任务链执行过程显示
        if (message.steps.isNotEmpty()) {
            TaskFlowContainer(steps = message.steps)
            Spacer(Modifier.height(16.dp))
        }

        // 图片附件
        if (message.imageUrl != null) {
            AsyncImage(
                model = message.imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(20.dp)
                    ),
                contentScale = ContentScale.Fit
            )
        }

        // 消息内容
        if (message.content.isNotBlank()) {
            SelectionContainer {
                Markdown(
                    content = message.content,
                    colors = markdownColor(
                        text = MaterialTheme.colorScheme.onSurface,
                        codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    ),
                    typography = markdownTypography(
                        h1 = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = BrandPrimary
                        ),
                        h2 = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = BrandPrimary
                        ),
                        h3 = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = BrandPrimary
                        ),
                        text = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                        code = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                )
            }

            // HTML 预览按钮
            if (htmlBlock != null) {
                Button(
                    onClick = { onPreviewHtml(htmlBlock) },
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandPrimary.copy(alpha = 0.1f),
                        contentColor = BrandPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("预览 HTML / 运行代码", style = MaterialTheme.typography.labelMedium)
                }
            }

            // 底部操作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(message.content)) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Share,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                )
            }
        }
    }
}
