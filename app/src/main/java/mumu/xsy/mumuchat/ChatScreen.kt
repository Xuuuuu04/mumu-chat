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

    // 导航抽屉
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SidebarContent(
                viewModel = viewModel,
                onSettingsClick = { showSettings = true }
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 动态渐变背景
            MeshBackground(isDark = isDark)

            Scaffold(
                topBar = {
                    ChatTopBar(
                        selectedModel = viewModel.settings.selectedModel,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onProfileClick = { showProfile = true },
                        onModelSelect = { model ->
                            viewModel.updateSettings(
                                viewModel.settings.copy(selectedModel = model)
                            )
                        },
                        availableModels = viewModel.settings.availableModels
                    )
                },
                containerColor = Color.Transparent,
                bottomBar = {
                    ChatInputDock(
                        viewModel = viewModel,
                        onSend = { viewModel.sendMessage(context, it) }
                    )
                }
            ) { paddingValues ->
                ChatMessagesArea(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    onPreviewHtml = { previewHtmlContent = it }
                )
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
 * 使用 WebView 渲染 HTML 内容
 */
@Composable
fun HtmlPreviewDialog(
    htmlContent: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.White
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
                            tint = Color.Black
                        )
                    }
                }

                // WebView 渲染区域
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(
                            null,
                            htmlContent,
                            "text/html",
                            "utf-8",
                            null
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
