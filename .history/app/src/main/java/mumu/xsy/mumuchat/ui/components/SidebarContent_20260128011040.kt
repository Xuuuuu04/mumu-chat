package mumu.xsy.mumuchat.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import mumu.xsy.mumuchat.ChatViewModel

/**
 * 侧边栏内容组件
 * 包含会话列表、文件夹管理和设置入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarContent(
    viewModel: ChatViewModel,
    snackbarHostState: SnackbarHostState,
    onSettingsClick: () -> Unit
) {
    var showRenameDialog by remember { mutableStateOf<String?>(null) } // SessionId
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newFolderName by remember { mutableStateOf("") }
    var sessionQuery by remember { mutableStateOf("") }
    var showExportMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folderExpandedState = remember { mutableStateMapOf<String, Boolean>() }
    var folderMenuFor by remember { mutableStateOf<String?>(null) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerTonalElevation = 0.dp,
        modifier = Modifier.width(320.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(24.dp)
        ) {
            // 应用标题区域
            Row(verticalAlignment = Alignment.CenterVertically) {
                MuLingLogo(size = 40.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "木灵",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        "水墨版 v2.0",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // 新建对话按钮 - 还原展示图的黑底圆角风格
            Button(
                onClick = { viewModel.createNewChat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A1A1A),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("开启新篇章", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }

            Spacer(Modifier.height(24.dp))

            // 导出按钮
            Box {
                TextButton(
                    onClick = { showExportMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("导出对话", style = MaterialTheme.typography.bodyMedium)
                }

                DropdownMenu(
                    expanded = showExportMenu,
                    onDismissRequest = { showExportMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("复制 Markdown") },
                        onClick = {
                            showExportMenu = false
                            val markdown = viewModel.exportCurrentSessionToMarkdown()
                            if (markdown.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(markdown))
                                scope.launch { snackbarHostState.showSnackbar("已复制到剪贴板 (Markdown)") }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("无会话可导出") }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("复制纯文本") },
                        onClick = {
                            showExportMenu = false
                            val text = viewModel.exportCurrentSessionToPlainText()
                            if (text.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(text))
                                scope.launch { snackbarHostState.showSnackbar("已复制到剪贴板 (纯文本)") }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("无会话可导出") }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Article, null) }
                    )

                    DropdownMenuItem(
                        text = { Text("复制 HTML") },
                        onClick = {
                            showExportMenu = false
                            val html = viewModel.exportCurrentSessionToHtml()
                            if (html.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(html))
                                scope.launch { snackbarHostState.showSnackbar("已复制到剪贴板 (HTML)") }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("无会话可导出") }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Code, null) }
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = { Text("分享 Markdown") },
                        onClick = {
                            showExportMenu = false
                            val markdown = viewModel.exportCurrentSessionToMarkdown()
                            if (markdown.isNotEmpty()) {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/markdown"
                                    putExtra(android.content.Intent.EXTRA_TEXT, markdown)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "分享对话"))
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("无会话可导出") }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Share, null) }
                    )

                    DropdownMenuItem(
                        text = { Text("分享纯文本") },
                        onClick = {
                            showExportMenu = false
                            val text = viewModel.exportCurrentSessionToPlainText()
                            if (text.isNotEmpty()) {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "分享对话"))
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("无会话可导出") }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Share, null) }
                    )

                    DropdownMenuItem(
                        text = { Text("分享 HTML") },
                        onClick = {
                            showExportMenu = false
                            val html = viewModel.exportCurrentSessionToHtml()
                            if (html.isNotEmpty()) {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/html"
                                    putExtra(android.content.Intent.EXTRA_TEXT, html)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "分享对话"))
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("无会话可导出") }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Share, null) }
                    )

                    DropdownMenuItem(
                        text = { Text("导出 PDF 并分享") },
                        onClick = {
                            showExportMenu = false
                            val pdf = viewModel.exportCurrentSessionToPdfFile(context)
                            if (pdf != null) {
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", pdf)
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "分享 PDF"))
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("PDF 导出失败") }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = sessionQuery,
                onValueChange = { sessionQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { 
                    Text(
                        "寻迹旧日对话...", 
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ) 
                },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent
                )
            )

            Spacer(Modifier.height(24.dp))

            // 文件夹标题栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    "归档分类",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { showNewFolderDialog = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 会话列表（按文件夹展示）
            LazyColumn(modifier = Modifier.weight(1f)) {
                val query = sessionQuery.trim()

                // 1. 无文件夹的会话
                val ungrouped = viewModel.sessions.filter {
                    it.folder == null && (query.isBlank() || it.title.contains(query, ignoreCase = true))
                }
                items(items = ungrouped, key = { it.id }) { session ->
                    SessionItem(
                        session = session,
                        viewModel = viewModel,
                        onRename = {
                            showRenameDialog = session.id
                            newTitle = session.title
                        }
                    )
                }

                // 2. 按文件夹分类
                viewModel.settings.folders.orEmpty().forEach { folderName ->
                    val folderSessions = viewModel.sessions.filter {
                        it.folder == folderName && (query.isBlank() || it.title.contains(query, ignoreCase = true))
                    }
                    if (query.isNotBlank() && folderSessions.isEmpty()) return@forEach
                    item(key = "folder_$folderName") {
                        val folderExpanded = folderExpandedState[folderName] ?: false
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { folderExpandedState[folderName] = !folderExpanded }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (folderExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                                Icon(
                                    Icons.Default.List,
                                    null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    folderName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.weight(1f))
                                Text("${folderSessions.size}", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.MoreVert,
                                    null,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { folderMenuFor = folderName },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = folderMenuFor == folderName,
                                onDismissRequest = { folderMenuFor = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("删除文件夹") },
                                    onClick = {
                                        viewModel.deleteFolder(folderName)
                                        folderExpandedState.remove(folderName)
                                        folderMenuFor = null
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Delete, null, tint = Color.Red)
                                    }
                                )
                            }
                        }

                        if (folderExpanded) {
                            Column(modifier = Modifier.padding(start = 16.dp)) {
                                folderSessions.forEach { session ->
                                    SessionItem(
                                        session = session,
                                        viewModel = viewModel,
                                        onRename = {
                                            showRenameDialog = session.id
                                            newTitle = session.title
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outline
            )

            // 设置入口
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSettingsClick() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Settings,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(16.dp))
                Text("模型与Key配置")
            }
        }
    }

    // 重命名对话框
    if (showRenameDialog != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("为对话题名", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSession(showRenameDialog!!, newTitle)
                        showRenameDialog = null
                    }
                ) {
                    Text("定稿")
                }
            }
        )
    }

    // 新建文件夹对话框
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("新建归档分类", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text("分类名称") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFolder(newFolderName)
                        newFolderName = ""
                        showNewFolderDialog = false
                    }
                ) {
                    Text("建立")
                }
            }
        )
    }
}
