package mumu.xsy.mumuchat.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mumu.xsy.mumuchat.ChatViewModel

/**
 * 侧边栏内容组件
 * 包含会话列表、文件夹管理和设置入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarContent(
    viewModel: ChatViewModel,
    onSettingsClick: () -> Unit
) {
    var showRenameDialog by remember { mutableStateOf<String?>(null) } // SessionId
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newFolderName by remember { mutableStateOf("") }

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
                MuMuLogo(size = 44.dp, isAnimating = true)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "MuMu Chat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Super Agent v2.0",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // 新建对话按钮
            Button(
                onClick = { viewModel.createNewChat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .shadow(12.dp, RoundedCornerShape(26.dp)),
                shape = RoundedCornerShape(26.dp)
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("新对话", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(24.dp))

            // 文件夹标题栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    "Folders",
                    style = MaterialTheme.typography.labelSmall,
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
                // 1. 无文件夹的会话
                val ungrouped = viewModel.sessions.filter { it.folder == null }
                itemsIndexed(ungrouped) { _, session ->
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
                viewModel.settings.folders.forEach { folderName ->
                    val folderSessions = viewModel.sessions.filter { it.folder == folderName }
                    item {
                        var folderExpanded by remember { mutableStateOf(false) }
                        var showFolderMenu by remember { mutableStateOf(false) }

                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { folderExpanded = !folderExpanded }
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
                                        .clickable { showFolderMenu = true },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = showFolderMenu,
                                onDismissRequest = { showFolderMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("删除文件夹") },
                                    onClick = {
                                        viewModel.deleteFolder(folderName)
                                        showFolderMenu = false
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
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameSession(showRenameDialog!!, newTitle)
                        showRenameDialog = null
                    }
                ) {
                    Text("保存")
                }
            }
        )
    }

    // 新建文件夹对话框
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    placeholder = { Text("文件夹名称") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createFolder(newFolderName)
                        newFolderName = ""
                        showNewFolderDialog = false
                    }
                ) {
                    Text("创建")
                }
            }
        )
    }
}
