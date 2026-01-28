package mumu.xsy.mumuchat

import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import mumu.xsy.mumuchat.ui.components.*
import mumu.xsy.mumuchat.ui.dialogs.ProfileDialog
import mumu.xsy.mumuchat.ui.dialogs.SettingsDialog

/**
 * 聊天主界面
 * 应用的核心 UI 容器，包含导航抽屉、消息列表和输入区域
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var previewHtmlContent by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchPos by remember { mutableStateOf(0) }

    val messages = viewModel.currentMessages
    val matchIndices = remember(messages, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) emptyList()
        else messages.mapIndexedNotNull { index, msg ->
            if (msg.content.contains(q, ignoreCase = true)) index else null
        }
    }
    val highlightIndex = matchIndices.getOrNull(searchPos)

    // 导航抽屉
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SidebarContent(
                viewModel = viewModel,
                onSessionSelected = { id ->
                    scope.launch { drawerState.close() }
                    viewModel.selectSession(id)
                },
                onSettingsClick = { 
                    scope.launch { drawerState.close() }
                    showSettings = true 
                },
                onProfileClick = {
                    scope.launch { drawerState.close() }
                    showProfile = true
                },
                currentSessionId = viewModel.currentSessionId
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 动态渐变背景
            XuanPaperBackground(isDark = isDark)

            Scaffold(
                topBar = {
                    ChatTopBar(
                        selectedModel = viewModel.settings.selectedModel,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onSearchClick = { searchOpen = !searchOpen },
                        onProfileClick = { showProfile = true },
                        onModelSelect = { model ->
                            viewModel.updateSettings(
                                viewModel.settings.copy(selectedModel = model)
                            )
                        },
                        availableModels = viewModel.settings.availableModels.orEmpty()
                    )
                },
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    ChatInputDock(
                        viewModel = viewModel,
                        onSend = { viewModel.sendMessage(context, it) }
                    )
                }
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    if (searchOpen) {
                        ChatSearchPanel(
                            query = searchQuery,
                            matchCount = matchIndices.size,
                            currentMatch = searchPos.coerceIn(0, (matchIndices.size - 1).coerceAtLeast(0)),
                            onQueryChange = {
                                searchQuery = it
                                searchPos = 0
                                matchIndices.firstOrNull()?.let { idx ->
                                    scope.launch { listState.animateScrollToItem(idx) }
                                }
                            },
                            onPrev = {
                                if (matchIndices.isNotEmpty()) {
                                    searchPos = (searchPos - 1 + matchIndices.size) % matchIndices.size
                                    scope.launch { listState.animateScrollToItem(matchIndices[searchPos]) }
                                }
                            },
                            onNext = {
                                if (matchIndices.isNotEmpty()) {
                                    searchPos = (searchPos + 1) % matchIndices.size
                                    scope.launch { listState.animateScrollToItem(matchIndices[searchPos]) }
                                }
                            },
                            onClose = {
                                searchOpen = false
                                searchQuery = ""
                                searchPos = 0
                            }
                        )
                    }

                    ChatMessagesArea(
                        viewModel = viewModel,
                        listState = listState,
                        highlightIndex = highlightIndex,
                        modifier = Modifier.weight(1f),
                        onPreviewHtml = { previewHtmlContent = it }
                    )
                }
            }
        }
    }

    // 设置对话框
    if (showSettings) {
        SettingsDialog(viewModel = viewModel, onDismiss = { showSettings = false })
    }

    // 个性化配置对话框
    if (showProfile) {
        ProfileDialog(viewModel = viewModel, onDismiss = { showProfile = false })
    }

    // HTML 预览对话框
    if (previewHtmlContent != null) {
        HtmlPreviewDialog(
            htmlContent = previewHtmlContent!!,
            onDismiss = { previewHtmlContent = null }
        )
    }
}

/**
 * HTML 预览对话框
 * 使用 WebView 渲染 HTML 内容，修复内存泄漏问题
 */
@Composable
fun HtmlPreviewDialog(
    htmlContent: String,
    onDismiss: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background // 使用完全不透明的背景色，防止内容重叠
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 关闭按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // WebView 渲染区域（修复内存泄漏）
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                        }.also { webView = it }
                    },
                    update = { view ->
                        view.loadDataWithBaseURL(
                            null,
                            htmlContent,
                            "text/html",
                            "utf-8",
                            null
                        )
                    },
                    modifier = Modifier.weight(1f)
                )

                // 确保 WebView 在关闭时销毁
                DisposableEffect(Unit) {
                    onDispose {
                        webView?.apply {
                            clearHistory()
                            clearCache(true)
                            clearFormData()
                            destroy()
                        }
                        webView = null
                    }
                }
            }
        }
    }
}
