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
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mumu.xsy.mumuchat.ChatViewModel
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
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
    val scope = rememberCoroutineScope()

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        viewModel.selectedImageUris.clear()
        viewModel.selectedImageUris.addAll(uris)
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val content = readTextFromUri(context, it)
                    viewModel.inputDraft = viewModel.inputDraft + "\n[文件解析]:\n$content"
                } catch (e: Exception) {
                    viewModel.inputDraft = viewModel.inputDraft + "\n[文件解析失败]: ${e.message}"
                }
            }
        }
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
                                text = { Text("文档", style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    fileLauncher.launch("*/*")
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
                    val isEnabled = text.isNotBlank() || viewModel.selectedImageUris.isNotEmpty()
                    
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

private suspend fun readTextFromUri(
    context: android.content.Context,
    uri: android.net.Uri,
    maxBytes: Int = 256 * 1024
): String = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri) ?: return@withContext ""
    input.use { stream ->
        val buffer = ByteArray(8 * 1024)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            val allowed = minOf(read, maxBytes - total)
            if (allowed > 0) {
                out.write(buffer, 0, allowed)
                total += allowed
            }
            if (total >= maxBytes) break
        }
        val bytes = out.toByteArray()
        var hasZero = false
        for (b in bytes) {
            if (b.toInt() == 0) {
                hasZero = true
                break
            }
        }
        if (hasZero) return@withContext "[文件包含二进制内容，已跳过]"
        val text = bytes.toString(Charsets.UTF_8)
        if (total >= maxBytes) text + "\n...(已截断)" else text
    }
}
