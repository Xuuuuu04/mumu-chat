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
import android.widget.Toast
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
    val context = LocalContext.current
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
                onSaveImages = { urls -> viewModel.saveImagesToGallery(context, urls) },
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
    onSaveImages: (List<String>) -> Int,
    onPreviewHtml: (String) -> Unit
) {
    val isUser = message.role == MessageRole.USER
    val isDark = isSystemInDarkTheme()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuExpanded by remember(message.id) { mutableStateOf(false) }
    val images = remember(message.imageUrl, message.imageUrls) {
        val urls = message.imageUrls.orEmpty()
        if (urls.isNotEmpty()) urls else message.imageUrl?.let { listOf(it) } ?: emptyList()
    }

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
                MuLingLogo(size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "木灵助手",
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

            if (images.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("保存图片") },
                    onClick = {
                        menuExpanded = false
                        val saved = onSaveImages(images)
                        Toast.makeText(context, "已保存 $saved 张图片", Toast.LENGTH_SHORT).show()
                    },
                    leadingIcon = { Icon(Icons.Default.Download, null) }
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
                    .padding(bottom = 12.dp)
                    .sizeIn(maxWidth = 260.dp, maxHeight = 260.dp)
                    .border(
                        0.5.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(4.dp)
                    )
            )
        }

        Surface(
            color = if (isDark) UserBubbleDark.copy(alpha = 0.6f) else UserBubbleLight.copy(alpha = 0.5f),
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        null,
                        modifier = Modifier
                            .size(13.dp)
                            .clickable { onEdit() },
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.35f)
                    )
                    Icon(
                        Icons.Default.ContentCopy,
                        null,
                        modifier = Modifier
                            .size(13.dp)
                            .clickable { clipboard.setText(AnnotatedString(message.content)) },
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.35f)
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

    Row(modifier = Modifier.fillMaxWidth()) {
        // 左侧墨线 (Ink Line)
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            BrandPrimary.copy(alpha = 0.2f),
                            BrandPrimary.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    )
                )
        )

        Spacer(Modifier.width(20.dp))

        Column(modifier = Modifier.weight(1f)) {
            // 任务链执行过程显示
            val steps = message.steps.orEmpty()
            if (steps.isNotEmpty()) {
                TaskFlowContainer(steps = steps)
                Spacer(Modifier.height(20.dp))
            }

            // 图片附件
            if (message.imageUrl != null) {
                ChatImage(
                    imageUrl = message.imageUrl,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .fillMaxWidth()
                        .border(
                            0.5.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(4.dp)
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
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Markdown(
                            content = message.content,
                            colors = markdownColor(
                                text = MaterialTheme.colorScheme.onSurface,
                                codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                                codeText = BrandPrimary,
                                linkText = Color(0xFF2A5CAA), // 靛蓝/墨蓝
                                inlineCodeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                inlineCodeText = BrandPrimary,
                                dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            ),
                            typography = markdownTypography(
                                h1 = MaterialTheme.typography.titleLarge.copy(
                                    color = BrandPrimary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                ),
                                h2 = MaterialTheme.typography.titleMedium.copy(
                                    color = BrandPrimary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                h3 = MaterialTheme.typography.bodyLarge.copy(
                                    color = BrandPrimary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                ),
                                text = MaterialTheme.typography.bodyLarge,
                                code = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    background = Color.Transparent // 由容器处理
                                ),
                                quote = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                ),
                                link = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
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
