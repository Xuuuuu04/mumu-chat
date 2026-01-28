package mumu.xsy.mumuchat.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mumu.xsy.mumuchat.ChatViewModel
import mumu.xsy.mumuchat.ui.theme.BrandPrimary

/**
 * 设置对话框组件
 * 包含 API 配置和模型管理功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(viewModel.settings.baseUrl) }
    var key by remember { mutableStateOf(viewModel.settings.apiKey) }
    var exaKey by remember { mutableStateOf(viewModel.settings.exaApiKey) }
    var newModelName by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var showExaKey by remember { mutableStateOf(false) }

    // State for selected new models
    var selectedNewModels by remember { mutableStateOf<Set<String>>(emptySet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "灵犀设定",
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("服务地址 (Base URL)") },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("API 密钥 (API Key)") },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )
                }

                item {
                    OutlinedTextField(
                        value = exaKey,
                        onValueChange = { exaKey = it },
                        label = { Text("Exa 搜索密钥 (Exa Search Key)") },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showExaKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showExaKey = !showExaKey }) {
                                Icon(
                                    if (showExaKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )
                }

                item {
                    HorizontalDivider()
                }

                item {
                    Text(
                        "灵犀名录",
                        style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 1.sp)
                    )
                }

                // 手动添加模型
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newModelName,
                            onValueChange = { newModelName = it },
                            label = { Text("录入新灵犀") },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            shape = RoundedCornerShape(8.dp)
                        )
                        IconButton(
                            onClick = {
                                viewModel.addModel(newModelName)
                                newModelName = ""
                            },
                            enabled = newModelName.isNotBlank()
                        ) {
                            Icon(
                                Icons.Default.AddCircle,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // 模型列表
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "可用模型列表",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { viewModel.fetchAvailableModels() }) {
                            Icon(
                                Icons.Default.Refresh,
                                null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("拉取更新")
                        }
                    }
                }

                items(viewModel.settings.availableModels) { modelId ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modelId,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.removeModel(modelId) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // 新发现的模型
                val newModels = viewModel.settings.fetchedModels.filter {
                    !viewModel.settings.availableModels.contains(it)
                }

                if (newModels.isNotEmpty()) {
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "发现新模型 (${newModels.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.weight(1f))
                            if (selectedNewModels.isNotEmpty()) {
                                TextButton(onClick = {
                                    viewModel.addModels(selectedNewModels.toList())
                                    selectedNewModels = emptySet()
                                }) {
                                    Text("添加选中 (${selectedNewModels.size})")
                                }
                            }
                        }
                    }

                    items(newModels) { modelId ->
                        val isChecked = selectedNewModels.contains(modelId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedNewModels = if (isChecked) {
                                        selectedNewModels - modelId
                                    } else {
                                        selectedNewModels + modelId
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = {
                                    selectedNewModels = if (it) {
                                        selectedNewModels + modelId
                                    } else {
                                        selectedNewModels - modelId
                                    }
                                }
                            )
                            Text(
                                modelId,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.updateSettings(
                        viewModel.settings.copy(
                            baseUrl = url,
                            apiKey = key,
                            exaApiKey = exaKey
                        )
                    )
                    onDismiss()
                }
            ) {
                Text("保存")
            }
        }
    )
}
