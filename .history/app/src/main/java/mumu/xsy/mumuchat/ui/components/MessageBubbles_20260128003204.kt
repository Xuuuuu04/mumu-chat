package mumu.xsy.mumuchat.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    listState: LazyListState,
    highlightIndex: Int?,
    modifier: Modifier = Modifier,
    onPreviewHtml: (String) -> Unit
) {
    val messages = viewModel.currentMessages
    val isNearBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= messages.lastIndex - 1
        }
    }

    // 当消息数量变化时，滚动到底部（避免首次加载时自动滚动）
    LaunchedEffect(messages.size, messages.lastOrNull()?.timestamp) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val lastContentLen = messages.lastOrNull()?.content?.length ?: 0
    val lastStepsSize = messages.lastOrNull()?.steps?.size ?: 0
    LaunchedEffect(lastContentLen, lastStepsSize) {
        if (messages.isNotEmpty() && isNearBottom) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 32.dp, start = 20.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        itemsIndexed(
            items = messages,
            key = { _, msg -> msg.id }
        ) { index, message ->
            MessageItem(
                message = message,
                isStreaming = viewModel.isGenerating && index == messages.lastIndex && message.role == MessageRole.ASSISTANT,
                isLastAssistant = index == messages.lastIndex && message.role == MessageRole.ASSISTANT,
                isHighlighted = highlightIndex == index,
                messageIndex = index,
                onEdit = { viewModel.editMessage(index) },
                onRegenerate = { viewModel.regenerateLastResponse() },
                onRegenerateAt = { viewModel.regenerateAssistantAt(index) },
                onQuote = { viewModel.quoteMessage(index) },
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
    isStreaming: Boolean,
    isLastAssistant: Boolean,
    isHighlighted: Boolean,
    messageIndex: Int,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onRegenerateAt: () -> Unit,
    onQuote: () -> Unit,
    onPreviewHtml: (String) -> Unit
) {
    val isUser = message.role == MessageRole.USER
    val isDark = isSystemInDarkTheme()
    val clipboard = LocalClipboardManager.current
    var menuExpanded by remember(message.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { menuExpanded = true }
            ),
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
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isUser) {
            UserBubble(
                message = message,
                isDark = isDark,
                isHighlighted = isHighlighted,
                onEdit = onEdit
            )
        } else {
            AiBubble(
                message = message,
                isDark = isDark,
                isStreaming = isStreaming,
                isLastAssistant = isLastAssistant,
                isHighlighted = isHighlighted,
                onRegenerate = onRegenerate,
                onPreviewHtml = onPreviewHtml
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("复制") },
                onClick = {
                    menuExpanded = false
                    clipboard.setText(AnnotatedString(message.content))
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
            )
            DropdownMenuItem(
                text = { Text("引用回复") },
                onClick = {
                    menuExpanded = false
                    onQuote()
                },
                leadingIcon = { Icon(Icons.Default.FormatQuote, null) }
            )
            if (!isUser) {
                DropdownMenuItem(
                    text = { Text("重新生成") },
                    onClick = {
                        menuExpanded = false
                        onRegenerateAt()
                    },
                    leadingIcon = { Icon(Icons.Default.Refresh, null) }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("编辑") },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                )
            }
        }
    }
}

/**
 * 优化的图片加载组件
 * 支持加载状态、错误处理和缓存配置
 */
@Composable
fun ChatImage(
    imageUrl: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp)
) {
    val context = LocalContext.current

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(true)
            .memoryCacheKey(imageUrl)
            .build(),
        contentDescription = null,
        modifier = modifier.clip(shape),
        contentScale = contentScale,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape
                    ),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = BrandPrimary
                )
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        shape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Warning,
                        null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        "加载失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}

/**
 * 用户消息气泡组件
 */
@Composable
fun UserBubble(
    message: ChatMessage,
    isDark: Boolean,
    isHighlighted: Boolean,
    onEdit: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    Column(horizontalAlignment = Alignment.End) {
        // 图片附件
        if (message.imageUrl != null) {
            ChatImage(
                imageUrl = message.imageUrl,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .sizeIn(maxWidth = 260.dp, maxHeight = 260.dp)
                    .border(
                        0.5.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(12.dp)
                    )
            )
        }

        Surface(
            color = if (isDark) UserBubbleDark else UserBubbleLight,
            shape = RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 26.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        null,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onEdit() },
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                    )
                    Icon(
                        Icons.Default.ContentCopy,
                        null,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { clipboard.setText(AnnotatedString(message.content)) },
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.4f)
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
    isStreaming: Boolean,
    isLastAssistant: Boolean,
    isHighlighted: Boolean,
    onRegenerate: () -> Unit,
    onPreviewHtml: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var streamedContent by remember(message.id) { mutableStateOf(message.content) }
    val highlightBorder = if (isHighlighted) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)) else null

    LaunchedEffect(message.id, isStreaming) {
        if (!isStreaming) {
            streamedContent = message.content
            return@LaunchedEffect
        }
        while (isActive) {
            streamedContent = message.content
            delay(200)
        }
    }

    // 检测 HTML 代码块
    val htmlBlock = remember(message.content) {
        "```html\\s+(.*?)\\s+```".toRegex(RegexOption.DOT_MATCHES_ALL)
            .find(message.content)?.groupValues?.get(1)
    }

    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        border = highlightBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(if (isHighlighted) 10.dp else 0.dp)) {
        // 任务链执行过程显示
        if (message.steps.isNotEmpty()) {
            TaskFlowContainer(steps = message.steps)
            Spacer(Modifier.height(16.dp))
        }

        // 图片附件（优化加载）
        if (message.imageUrl != null) {
            ChatImage(
                imageUrl = message.imageUrl,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
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
                if (isStreaming) {
                    Text(
                        text = streamedContent,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Markdown(
                        content = message.content,
                        colors = markdownColor(
                            text = MaterialTheme.colorScheme.onSurface,
                            codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
                        ),
                        typography = markdownTypography(
                            h1 = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = BrandPrimary,
                                letterSpacing = 1.sp
                            ),
                            h2 = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = BrandPrimary,
                                letterSpacing = 0.5.sp
                            ),
                            h3 = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = BrandPrimary
                            ),
                            text = MaterialTheme.typography.bodyLarge.copy(
                                lineHeight = 28.sp,
                                letterSpacing = 0.3.sp
                            ),
                            code = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                background = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                            )
                        )
                    )
                }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLastAssistant) {
                        IconButton(
                            onClick = onRegenerate,
                            enabled = !isStreaming,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(message.content)) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
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
}
