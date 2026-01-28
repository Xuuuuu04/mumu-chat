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
        Column(modifier = Modifier.fillMaxSize()) {
            // === 1. 固定顶部区 (Header) ===
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MuLingLogo(size = 42.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "木灵",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "水墨版 v2.0",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))

                // 开启新篇章 - 核心操作
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

                Spacer(Modifier.height(16.dp))

                // 寻迹搜索
                OutlinedTextField(
                    value = sessionQuery,
                    onValueChange = { sessionQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { 
                        Text(
                            "寻迹旧日对话...", 
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        ) 
                    },
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    )
                )
            }

            // === 2. 滚动内容区 (Body) ===
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                val query = sessionQuery.trim()
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 近期寻迹标题
                    item {
                        Text(
                            "近期寻迹",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                        )
                    }

                    // 1. 无文件夹的近期会话
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

                    // 归档卷轴标题
                    item {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, bottom = 8.dp)
                        ) {
                            Text(
                                "归档卷轴",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { showNewFolderDialog = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.CreateNewFolder, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                            }
                        }
                    }

                    // 2. 按文件夹分类
                    viewModel.settings.folders.orEmpty().forEach { folderName ->
                        val folderSessions = viewModel.sessions.filter {
                            it.folder == folderName && (query.isBlank() || it.title.contains(query, ignoreCase = true))
                        }
                        if (query.isNotBlank() && folderSessions.isEmpty()) return@forEach
                        
                        item(key = "folder_$folderName") {
                            val folderExpanded = folderExpandedState[folderName] ?: false
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { folderExpandedState[folderName] = !folderExpanded }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (folderExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                        null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        folderName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${folderSessions.size}", 
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    
                                    IconButton(
                                        onClick = { folderMenuFor = folderName },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    }
                                }

                                if (folderExpanded) {
                                    Column(modifier = Modifier.padding(start = 12.dp)) {
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
                }
            }

            // === 3. 固定底部区 (Footer) ===
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                Spacer(Modifier.height(8.dp))
                
                // 导出按钮 - 移至底部更合理
                TextButton(
                    onClick = { showExportMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                ) {
                    Icon(Icons.Default.IosShare, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("对话导出", style = MaterialTheme.typography.bodyMedium)
                }

                // 灵犀设定
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSettingsClick() }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Tune,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "灵犀设定",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
