package mumu.xsy.mumuchat.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mumu.xsy.mumuchat.ChatViewModel
import mumu.xsy.mumuchat.ui.theme.BrandPrimary

/**
 * 个性化配置对话框组件
 * 包含 AI Persona 设置和长期记忆管理功能
 */
@Composable
fun ProfileDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    var persona by remember { mutableStateOf(viewModel.settings.userPersona) }
    var newMemory by remember { mutableStateOf("") }
    val memories = viewModel.settings.memories.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "心性设定",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            ) 
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 个性化设定
                item {
                    Text(
                        "设定 AI 角色心性 (Persona)",
                        style = MaterialTheme.typography.labelLarge,
                        color = BrandPrimary
                    )
                }

                item {
                    OutlinedTextField(
                        value = persona,
                        onValueChange = { persona = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        shape = RoundedCornerShape(8.dp),
                        placeholder = { Text("描述 AI 的性格、语言风格等...") }
                    )
                }

                item {
                    HorizontalDivider()
                }

                // 长期记忆
                item {
                    Text(
                        "长期记忆",
                        style = MaterialTheme.typography.labelLarge,
                        color = BrandPrimary
                    )
                }

                if (memories.isEmpty()) {
                    item {
                        Text(
                            "暂无记忆",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                itemsIndexed(memories) { index, memory ->
                    MemoryItem(
                        text = memory,
                        onDelete = { viewModel.deleteMemory(index) },
                        onUpdate = { viewModel.updateMemory(index, it) }
                    )
                }

                // 添加记忆
                item {
                    OutlinedTextField(
                        value = newMemory,
                        onValueChange = { newMemory = it },
                        placeholder = { Text("添加记忆...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    viewModel.addMemory(newMemory)
                                    newMemory = ""
                                },
                                enabled = newMemory.isNotBlank()
                            ) {
                                Icon(
                                    Icons.Default.AddCircle,
                                    null,
                                    tint = if (newMemory.isNotBlank()) {
                                        BrandPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    }
                                )
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.updateSettings(
                        viewModel.settings.copy(userPersona = persona)
                    )
                    onDismiss()
                }
            ) {
                Text("完成")
            }
        }
    )
}

/**
 * 记忆项组件
 * 显示单条记忆，支持编辑和删除
 */
@Composable
fun MemoryItem(
    text: String,
    onDelete: () -> Unit,
    onUpdate: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(text) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (editing) {
                // 编辑模式
                BasicTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = { editing = false }) {
                        Text("取消")
                    }
                    TextButton(
                        onClick = {
                            onUpdate(editText)
                            editing = false
                        }
                    ) {
                        Text("保存")
                    }
                }
            } else {
                // 显示模式
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "• $text",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(8.dp)
                    )

                    IconButton(onClick = { editing = true }) {
                        Icon(
                            Icons.Default.Edit,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = BrandPrimary
                        )
                    }

                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
