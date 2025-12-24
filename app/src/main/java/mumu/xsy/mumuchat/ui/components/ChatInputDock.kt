package mumu.xsy.mumuchat.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
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
import coil.compose.AsyncImage
import mumu.xsy.mumuchat.ChatViewModel
import java.io.BufferedReader
import java.io.InputStreamReader

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
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        viewModel.selectedImageUri = uri
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val content = BufferedReader(
                    InputStreamReader(context.contentResolver.openInputStream(it))
                ).readText()
                viewModel.inputDraft = viewModel.inputDraft + "\n[文件解析]:\n$content"
            } catch (e: Exception) {
                viewModel.inputDraft = viewModel.inputDraft + "\n[文件解析失败]: ${e.message}"
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Column(modifier = Modifier.padding(12.dp)) {
                // 图片预览
                if (viewModel.selectedImageUri != null) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .size(70.dp)
                    ) {
                        AsyncImage(
                            model = viewModel.selectedImageUri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )

                        IconButton(
                            onClick = { viewModel.selectedImageUri = null },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .background(Color.Black.copy(0.6f), CircleShape)
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

                Row(verticalAlignment = Alignment.Bottom) {
                    // 附件菜单
                    var showMenu by remember { mutableStateOf(false) }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Default.Add,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("图片") },
                                onClick = {
                                    photoLauncher.launch("image/*")
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("文档") },
                                onClick = {
                                    fileLauncher.launch("*/*")
                                    showMenu = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Info, null)
                                }
                            )
                        }
                    }

                    // 输入框
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            if (text.isEmpty()) {
                                Text(
                                    "发个消息给 MuMu...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                                )
                            }

                            BasicTextField(
                                value = text,
                                onValueChange = {
                                    text = it
                                    viewModel.inputDraft = it
                                },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 6
                            )
                        }
                    }

                    // 发送按钮
                    val isEnabled = text.isNotBlank() || viewModel.selectedImageUri != null
                    IconButton(
                        onClick = {
                            onSend(text)
                            text = ""
                        },
                        enabled = isEnabled,
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (isEnabled) MaterialTheme.colorScheme.primary else Color.Transparent,
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            null,
                            tint = if (isEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
