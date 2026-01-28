package mumu.xsy.mumuchat.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mumu.xsy.mumuchat.ChatViewModel
import android.net.Uri
import android.content.Intent
import android.provider.OpenableColumns

/**
 * 聊天输入栏组件
 * 包含文本输入框、附件菜单和发送按钮
 */
@Composable
fun ChatInputDock(
    viewModel: ChatViewModel,
    onSend: (String) -> Unit
) {
    var text by remember(viewModel.inputDraft) { mutableStateOf(viewModel.inputDraft) }
    val context = LocalContext.current

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        viewModel.selectedImageUris.clear()
        viewModel.selectedImageUris.addAll(uris)
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        viewModel.selectedFileUris.clear()
        viewModel.selectedFileUris.addAll(uris)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // 图片预览
                if (viewModel.selectedImageUris.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp, start = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        viewModel.selectedImageUris.forEachIndexed { index, uri ->
                            Box(modifier = Modifier.size(60.dp)) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )

                                IconButton(
                                    onClick = {
                                        if (index in viewModel.selectedImageUris.indices) {
                                            viewModel.selectedImageUris.removeAt(index)
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-4).dp)
                                        .size(16.dp)
                                        .background(MaterialTheme.colorScheme.error.copy(0.8f), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (viewModel.selectedFileUris.isNotEmpty()) {
                    val scroll = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp, start = 4.dp)
                            .horizontalScroll(scroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        viewModel.selectedFileUris.forEachIndexed { index, uri ->
                            val name by rememberUriDisplayName(context, uri)
                            InputChip(
                                selected = true,
                                onClick = {},
                                label = {
                                    Text(
                                        text = name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                if (index in viewModel.selectedFileUris.indices) {
                                                    viewModel.selectedFileUris.removeAt(index)
                                                }
                                            }
                                    )
                                }
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 附件菜单
                    var showMenu by remember { mutableStateOf(false) }

                    Box(modifier = Modifier.padding(bottom = 2.dp)) {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("图片", style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    photoLauncher.launch("image/*")
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("文件", style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    fileLauncher.launch(arrayOf("*/*"))
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp))
                                }
                            )
                        }
                    }

                    // 输入框
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 2.dp)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            if (text.isEmpty()) {
                                Text(
                                    "在此书写消息...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.4f)
                                )
                            }

                            BasicTextField(
                                value = text,
                                onValueChange = {
                                    text = it
                                    viewModel.inputDraft = it
                                },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 20.sp
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 5
                            )
                        }
                    }

                    // 发送按钮
                    val isEnabled = text.isNotBlank() || viewModel.selectedImageUris.isNotEmpty() || viewModel.selectedFileUris.isNotEmpty()
                    
                    if (viewModel.isGenerating) {
                        IconButton(
                            onClick = { viewModel.stopGeneration() },
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .size(36.dp)
                                .background(
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                                    CircleShape
                                )
                                .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                onSend(text)
                                text = ""
                            },
                            enabled = isEnabled,
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .size(36.dp)
                                .background(
                                    if (isEnabled) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                null,
                                tint = if (isEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberUriDisplayName(context: android.content.Context, uri: Uri): State<String> {
    return produceState(initialValue = uri.lastPathSegment ?: "附件", key1 = uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                    }
            }.getOrNull() ?: (uri.lastPathSegment ?: "附件")
        }
    }
}
