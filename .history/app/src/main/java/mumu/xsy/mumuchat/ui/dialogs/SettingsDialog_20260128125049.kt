package mumu.xsy.mumuchat.ui.dialogs

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mumu.xsy.mumuchat.ChatViewModel
import mumu.xsy.mumuchat.McpServerConfig
import mumu.xsy.mumuchat.system.SystemIntents
import mumu.xsy.mumuchat.tools.ToolsCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val context = LocalContext.current
    var sysNonce by remember { mutableStateOf(0) }
    val requestNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        sysNonce += 1
    }
    var url by remember { mutableStateOf(viewModel.settings.baseUrl) }
    var key by remember { mutableStateOf(viewModel.settings.apiKey) }
    var exaKey by remember { mutableStateOf(viewModel.settings.exaApiKey) }
    var tavilyKey by remember { mutableStateOf(viewModel.settings.tavilyApiKey) }
    var newModelName by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var showExaKey by remember { mutableStateOf(false) }
    var showTavilyKey by remember { mutableStateOf(false) }
    var selectedNewModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var browseAllow by remember { mutableStateOf(viewModel.settings.browseAllowlist.orEmpty().joinToString("\n")) }
    var browseDeny by remember { mutableStateOf(viewModel.settings.browseDenylist.orEmpty().joinToString("\n")) }
    var enableLocalTools by remember { mutableStateOf(viewModel.settings.enableLocalTools == true) }
    var enablePublicApis by remember { mutableStateOf(viewModel.settings.enablePublicApis == true) }
    var enablePublicApiViki by remember { mutableStateOf(viewModel.settings.enablePublicApiViki == true) }
    var enablePublicApiTenApi by remember { mutableStateOf(viewModel.settings.enablePublicApiTenApi == true) }
    var enablePublicApiVvHan by remember { mutableStateOf(viewModel.settings.enablePublicApiVvHan == true) }
    var enablePublicApiQqsuu by remember { mutableStateOf(viewModel.settings.enablePublicApiQqsuu == true) }
    var enablePublicApi770a by remember { mutableStateOf(viewModel.settings.enablePublicApi770a == true) }
    var enableSerpSearch by remember { mutableStateOf(viewModel.settings.enableSerpSearch == true) }
    var enableSerpBaidu by remember { mutableStateOf(viewModel.settings.enableSerpBaidu == true) }
    var enableSerpDuckDuckGo by remember { mutableStateOf(viewModel.settings.enableSerpDuckDuckGo == true) }
    var searxngBaseUrl by remember { mutableStateOf(viewModel.settings.searxngBaseUrl) }
    var enableMcpTools by remember { mutableStateOf(viewModel.settings.enableMcpTools == true) }
    var mcpServersText by remember {
        mutableStateOf(
            viewModel.settings.mcpServers.orEmpty()
                .filter { it.endpoint.isNotBlank() }
                .joinToString("\n") { s ->
                    val token = s.authToken?.trim().orEmpty()
                    if (token.isBlank()) "${s.id}|${s.endpoint}" else "${s.id}|${s.endpoint}|$token"
                }
        )
    }
    var enableCalendarTools by remember { mutableStateOf(viewModel.settings.enableCalendarTools == true) }
    var enableNotificationTools by remember { mutableStateOf(viewModel.settings.enableNotificationTools == true) }
    var enableFileTools by remember { mutableStateOf(viewModel.settings.enableFileTools == true) }
    var enableSuperIsland by remember { mutableStateOf(viewModel.settings.enableSuperIsland == true) }
    var diagNonce by remember { mutableStateOf(0) }
    var actionNonce by remember { mutableStateOf(0) }
    var reminderNonce by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var selfCheckOpen by remember { mutableStateOf(false) }
    var selfCheckRunning by remember { mutableStateOf(false) }
    val selfCheckRows = remember { mutableStateListOf<ToolSelfCheckRow>() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f), // 提高不透明度，防止内容干扰
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部标题栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "灵犀设定",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            "配置连接与名录",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // 1. 核心连接配置 (Nexus Config)
                    item {
                        SettingsSection(title = "连接配置", icon = Icons.Default.Hub) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedTextField(
                                    value = url,
                                    onValueChange = { url = it },
                                    label = { Text("服务地址 (Base URL)", style = MaterialTheme.typography.bodySmall) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )
                                )

                                OutlinedTextField(
                                    value = key,
                                    onValueChange = { key = it },
                                    label = { Text("API 密钥 (API Key)", style = MaterialTheme.typography.bodySmall) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showApiKey = !showApiKey }) {
                                            Icon(if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, modifier = Modifier.size(20.dp))
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )
                                )

                                OutlinedTextField(
                                    value = exaKey,
                                    onValueChange = { exaKey = it },
                                    label = { Text("Exa 搜索密钥 (Optional)", style = MaterialTheme.typography.bodySmall) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    visualTransformation = if (showExaKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showExaKey = !showExaKey }) {
                                            Icon(if (showExaKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, modifier = Modifier.size(20.dp))
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )
                                )

                                OutlinedTextField(
                                    value = tavilyKey,
                                    onValueChange = { tavilyKey = it },
                                    label = { Text("Tavily 搜索密钥 (Optional)", style = MaterialTheme.typography.bodySmall) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    visualTransformation = if (showTavilyKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showTavilyKey = !showTavilyKey }) {
                                            Icon(if (showTavilyKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, modifier = Modifier.size(20.dp))
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )
                                )
                            }
                        }
                    }

                    // 2. 灵犀名录管理 (Model Library)
                    item {
                        SettingsSection(title = "灵犀名录", icon = Icons.AutoMirrored.Filled.MenuBook) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = newModelName,
                                        onValueChange = { newModelName = it },
                                        label = { Text("录入新灵犀", style = MaterialTheme.typography.bodySmall) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { viewModel.addModel(newModelName); newModelName = "" },
                                        enabled = newModelName.isNotBlank()
                                    ) {
                                        Icon(
                                            Icons.Default.AddCircle, 
                                            null, 
                                            tint = if (newModelName.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("可用名录", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    TextButton(onClick = { viewModel.fetchAvailableModels() }) {
                                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("同步云端", style = MaterialTheme.typography.labelSmall)
                                    }
                                }

                                // 模型列表卡片
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f))
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        viewModel.settings.availableModels.orEmpty().forEach { modelId ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(modelId, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                                IconButton(onClick = { viewModel.removeModel(modelId) }, modifier = Modifier.size(24.dp)) {
                                                    Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SettingsSection(title = "浏览策略", icon = Icons.Default.Public) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = browseAllow,
                                    onValueChange = { browseAllow = it },
                                    label = { Text("Allowlist（每行一个域名，可留空表示全放行）", style = MaterialTheme.typography.bodySmall) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )
                                )
                                OutlinedTextField(
                                    value = browseDeny,
                                    onValueChange = { browseDeny = it },
                                    label = { Text("Denylist（每行一个域名，优先级高于 Allowlist）", style = MaterialTheme.typography.bodySmall) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )
                                )
                            }
                        }
                    }

                    item {
                        SettingsSection(title = "公益 API", icon = Icons.Default.Cloud) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("启用公益 API", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Text("包含 60s/热榜/工具类接口", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = enablePublicApis, onCheckedChange = { enablePublicApis = it })
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("60s.viki.moe", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Switch(checked = enablePublicApiViki, onCheckedChange = { enablePublicApiViki = it })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("TenAPI（热榜/一言）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Switch(checked = enablePublicApiTenApi, onCheckedChange = { enablePublicApiTenApi = it })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("vvhan.com（工具集）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Switch(checked = enablePublicApiVvHan, onCheckedChange = { enablePublicApiVvHan = it })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("qqsuu.cn（60s图片）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Switch(checked = enablePublicApiQqsuu, onCheckedChange = { enablePublicApiQqsuu = it })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("770a.cn（60s图片）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Switch(checked = enablePublicApi770a, onCheckedChange = { enablePublicApi770a = it })
                                }
                            }
                        }
                    }

                    item {
                        SettingsSection(title = "网页搜索（SERP）", icon = Icons.Default.Search) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("启用 SERP 搜索", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Text("抓取搜索结果页，返回标题/链接/摘要", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = enableSerpSearch, onCheckedChange = { enableSerpSearch = it })
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Baidu", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Switch(checked = enableSerpBaidu, onCheckedChange = { enableSerpBaidu = it })
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("DuckDuckGo（HTML）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Switch(checked = enableSerpDuckDuckGo, onCheckedChange = { enableSerpDuckDuckGo = it })
                                }

                                OutlinedTextField(
                                    value = searxngBaseUrl,
                                    onValueChange = { searxngBaseUrl = it },
                                    label = { Text("SearXNG 地址（可选）", style = MaterialTheme.typography.bodySmall) },
                                    placeholder = { Text("https://search.example.com", style = MaterialTheme.typography.bodySmall) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )
                                )
                            }
                        }
                    }

                    item {
                        SettingsSection(title = "MCP（外部工具）", icon = Icons.Default.Extension) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("启用 MCP", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Text("通过 MCP Server 接入外部工具/数据源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = enableMcpTools, onCheckedChange = { enableMcpTools = it })
                                }

                                OutlinedTextField(
                                    value = mcpServersText,
                                    onValueChange = { mcpServersText = it },
                                    label = { Text("Servers（每行：id|endpoint|token可选）", style = MaterialTheme.typography.bodySmall) },
                                    placeholder = { Text("default|https://example.com/mcp|TOKEN", style = MaterialTheme.typography.bodySmall) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )
                                )
                            }
                        }
                    }

                    item {
                        SettingsSection(title = "系统能力", icon = Icons.Default.SettingsSuggest) {
                            val focusProtocol = remember(sysNonce) { viewModel.getFocusProtocolVersion() }
                            val notificationsOk = remember(sysNonce) { viewModel.canPostNotifications() }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.06f))
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val islandSupport = if (focusProtocol >= 3) "支持" else "不支持"
                                        Text("超级岛协议版本：v$focusProtocol（系统：$islandSupport）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("通知权限：${if (notificationsOk) "已开启" else "未开启"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedButton(onClick = { sysNonce += 1 }) { Text("刷新状态") }
                                            if (!notificationsOk && android.os.Build.VERSION.SDK_INT >= 33) {
                                                OutlinedButton(
                                                    onClick = {
                                                        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                                    }
                                                ) { Text("申请权限") }
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                }
                                            ) { Text("通知设置") }
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("日历工具", style = MaterialTheme.typography.bodyMedium)
                                        Text("创建日历事件需要显式开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = enableCalendarTools, onCheckedChange = { enableCalendarTools = it })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("通知/提醒工具", style = MaterialTheme.typography.bodyMedium)
                                        Text("设置计时器/提醒需要显式开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = enableNotificationTools, onCheckedChange = { enableNotificationTools = it })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("文件导入导出工具", style = MaterialTheme.typography.bodyMedium)
                                        Text("导入/导出会话文件需要显式开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = enableFileTools, onCheckedChange = { enableFileTools = it })
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("澎湃OS3 超级岛联动", style = MaterialTheme.typography.bodyMedium)
                                        Text("仅在支持超级岛的系统上生效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = enableSuperIsland, onCheckedChange = { enableSuperIsland = it })
                                }
                            }
                        }
                    }

                    item {
                        SettingsSection(title = "定时提醒", icon = Icons.Default.NotificationsActive) {
                            val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
                            val list = remember(reminderNonce, viewModel.reminders.size) { viewModel.listScheduledRemindersUi(100) }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { reminderNonce += 1 },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("刷新") }
                                    OutlinedButton(
                                        onClick = { viewModel.clearAllScheduledReminders() },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("清空") }
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f))
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (list.isEmpty()) {
                                            Text("暂无定时提醒", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            list.forEach { r ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        Text(r.title, style = MaterialTheme.typography.bodyMedium)
                                                        Text(
                                                            fmt.format(Date(r.atMs)),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    IconButton(onClick = { viewModel.cancelScheduledReminder(r.id) }) {
                                                        Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SettingsSection(title = "最近动作", icon = Icons.Default.History) {
                            val actions = remember(actionNonce, viewModel.recentActions.size) { viewModel.getRecentActionsUi(40) }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { actionNonce += 1 },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("刷新") }
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.clearRecentActions()
                                            actionNonce += 1
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("清空") }
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f))
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (actions.isEmpty()) {
                                            Text("暂无最近动作", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            actions.forEach { a ->
                                                val approvedText = when (a.approved) {
                                                    true -> "已允许"
                                                    false -> "已拒绝"
                                                    else -> "未确认"
                                                }
                                                val status = a.status?.let { " / $it" }.orEmpty()
                                                Text("${a.tool}: ${a.summary}（$approvedText$status）", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SettingsSection(title = "诊断面板", icon = Icons.Default.BugReport) {
                            val metrics = remember(diagNonce) { viewModel.getToolMetricsUi() }
                            val errors = remember(diagNonce) { viewModel.getToolErrorsUi(20) }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("启用本地动作工具", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Text("剪贴板/分享等需要显式开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = enableLocalTools, onCheckedChange = { enableLocalTools = it })
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { diagNonce += 1 },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("刷新") }
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.clearToolDiagnostics()
                                            diagNonce += 1
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("清空统计") }
                                }

                                OutlinedButton(
                                    onClick = {
                                        selfCheckOpen = true
                                        selfCheckRows.clear()
                                        selfCheckRunning = true
                                        scope.launch {
                                            runToolSelfCheck(viewModel, selfCheckRows)
                                            selfCheckRunning = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (selfCheckRunning) "自检中…" else "一键自检") }

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f))
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (metrics.isEmpty()) {
                                            Text("暂无工具统计", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            metrics.forEach { m ->
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(m.tool, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                                    Text("${m.calls} / ${m.errors} / ${"%.0f".format(m.avgMs)}ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f))
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (errors.isEmpty()) {
                                            Text("暂无最近错误", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            errors.asReversed().forEach { e ->
                                                Text("${e.tool}: ${e.message}", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    item { Spacer(Modifier.height(20.dp)) }
                }

                // 底部操作栏
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = {
                                val allow = browseAllow.lines().map { it.trim() }.filter { it.isNotBlank() }
                                val deny = browseDeny.lines().map { it.trim() }.filter { it.isNotBlank() }
                                val mcpServers = mcpServersText.lines()
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                    .mapNotNull { line ->
                                        val parts = line.split("|")
                                        val id = parts.getOrNull(0)?.trim().orEmpty()
                                        val endpoint = parts.getOrNull(1)?.trim().orEmpty()
                                        val token = parts.getOrNull(2)?.trim().takeIf { !it.isNullOrBlank() }
                                        if (id.isBlank() || endpoint.isBlank()) null else McpServerConfig(id = id, endpoint = endpoint, authToken = token)
                                    }
                                viewModel.updateSettings(
                                    viewModel.settings.copy(
                                        baseUrl = url,
                                        apiKey = key,
                                        exaApiKey = exaKey,
                                        tavilyApiKey = tavilyKey,
                                        browseAllowlist = allow,
                                        browseDenylist = deny,
                                        enableLocalTools = enableLocalTools,
                                        enablePublicApis = enablePublicApis,
                                        enablePublicApiViki = enablePublicApiViki,
                                        enablePublicApiTenApi = enablePublicApiTenApi,
                                        enablePublicApiVvHan = enablePublicApiVvHan,
                                        enablePublicApiQqsuu = enablePublicApiQqsuu,
                                        enablePublicApi770a = enablePublicApi770a,
                                        enableSerpSearch = enableSerpSearch,
                                        enableSerpBaidu = enableSerpBaidu,
                                        enableSerpDuckDuckGo = enableSerpDuckDuckGo,
                                        searxngBaseUrl = searxngBaseUrl,
                                        enableMcpTools = enableMcpTools,
                                        mcpServers = mcpServers,
                                        enableCalendarTools = enableCalendarTools,
                                        enableNotificationTools = enableNotificationTools,
                                        enableFileTools = enableFileTools,
                                        enableSuperIsland = enableSuperIsland
                                    )
                                )
                                onDismiss()
                            },
                            modifier = Modifier.weight(1.5f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text("保存配置", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (selfCheckOpen) {
        Dialog(
            onDismissRequest = { if (!selfCheckRunning) selfCheckOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.75f),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("工具自检", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("会自动跳过需要手工确认/权限的项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { if (!selfCheckRunning) selfCheckOpen = false }) { Text("关闭") }
                    }

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f))
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (selfCheckRows.isEmpty()) {
                                item {
                                    Text(if (selfCheckRunning) "正在运行…" else "暂无结果", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                items(selfCheckRows) { row ->
                                    val c = when (row.status) {
                                        "PASS" -> MaterialTheme.colorScheme.primary
                                        "SKIP" -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                    Column {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(row.tool, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                            Text("${row.status} ${row.ms}ms", style = MaterialTheme.typography.bodySmall, color = c)
                                        }
                                        if (row.detail.isNotBlank()) {
                                            Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class ToolSelfCheckRow(
    val tool: String,
    val status: String,
    val ms: Long,
    val detail: String
)

private suspend fun runToolSelfCheck(viewModel: ChatViewModel, out: MutableList<ToolSelfCheckRow>) {
    val names = ToolsCatalog.getToolsDefinition()
        .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
        .mapNotNull { it.getAsJsonObject("function")?.get("name")?.asString }
        .distinct()

    for (name in names) {
        val start = System.currentTimeMillis()
        val args = defaultArgsForTool(name)
        val sid = viewModel.currentSessionId ?: viewModel.sessions.firstOrNull()?.id
        if (sid != null && args.has("session_id") && args.get("session_id")?.asString == "__current__") {
            args.addProperty("session_id", sid)
        }
        val result = runToolWithAutoDenies(viewModel, name, args)
        val ms = System.currentTimeMillis() - start
        val row = buildSelfCheckRow(name, ms, result)
        withContext(Dispatchers.Main) { out.add(row) }
        delay(30)
    }
}

private fun defaultArgsForTool(name: String): JsonObject {
    return when (name) {
        "save_memory" -> JsonObject().apply { addProperty("fact", "工具自检：记忆") }
        "search_memories" -> JsonObject().apply { addProperty("query", "工具自检") }
        "upsert_memory" -> JsonObject().apply { addProperty("key", "selfcheck"); addProperty("value", "工具自检") }
        "get_memory" -> JsonObject().apply { addProperty("key", "selfcheck") }
        "delete_memory_by_key" -> JsonObject().apply { addProperty("key", "selfcheck") }
        "rename_memory_key" -> JsonObject().apply { addProperty("old_key", "selfcheck"); addProperty("new_key", "selfcheck2") }
        "memory_gc" -> JsonObject().apply { addProperty("max_entries", 50); addProperty("keep_recent_days", 30) }
        "search_chat_history" -> JsonObject().apply { addProperty("query", "你好"); addProperty("limit", 5) }
        "summarize_session_local" -> JsonObject().apply { addProperty("limit", 50) }
        "extract_todos_from_chat" -> JsonObject().apply { addProperty("limit", 50) }
        "save_note" -> JsonObject().apply { addProperty("title", "自检"); addProperty("content", "工具自检") }
        "list_notes" -> JsonObject().apply { addProperty("limit", 10) }
        "search_notes" -> JsonObject().apply { addProperty("query", "自检"); addProperty("limit", 10) }
        "copy_to_clipboard" -> JsonObject().apply { addProperty("text", "工具自检") }
        "share_text" -> JsonObject().apply { addProperty("text", "工具自检"); addProperty("title", "自检") }
        "search_sessions" -> JsonObject().apply { addProperty("query", "自检"); addProperty("limit", 5) }
        "rename_session" -> JsonObject().apply { addProperty("session_id", "__current__"); addProperty("title", "自检") }
        "move_session_to_folder" -> JsonObject().apply { addProperty("session_id", "__current__"); addProperty("folder", "自检") }
        "export_session" -> JsonObject().apply { addProperty("format", "markdown") }
        "exa_search" -> JsonObject().apply { addProperty("query", "OpenAI") }
        "tavily_search" -> JsonObject().apply { addProperty("query", "OpenAI"); addProperty("max_results", 5); addProperty("search_depth", "basic") }
        "browse_url" -> JsonObject().apply { addProperty("url", "https://example.com") }
        "serp_search" -> JsonObject().apply { addProperty("query", "OpenAI"); addProperty("engine", "duckduckgo"); addProperty("limit", 3); addProperty("page", 1) }
        "docs_add_text" -> JsonObject().apply { addProperty("title", "自检文档"); addProperty("content", "这是一个用于工具自检的本地文档。"); addProperty("tags", "selfcheck") }
        "docs_list" -> JsonObject().apply { addProperty("limit", 10) }
        "docs_search" -> JsonObject().apply { addProperty("query", "自检"); addProperty("limit", 10) }
        "calendar_create_event" -> JsonObject().apply {
            val now = System.currentTimeMillis()
            addProperty("title", "自检事件")
            addProperty("start_ms", now + 60_000L)
            addProperty("end_ms", now + 120_000L)
        }
        "calendar_insert_silent" -> JsonObject().apply {
            val now = System.currentTimeMillis()
            addProperty("title", "自检事件")
            addProperty("start_ms", now + 60_000L)
            addProperty("end_ms", now + 120_000L)
        }
        "calendar_list_events" -> JsonObject().apply {
            val now = System.currentTimeMillis()
            addProperty("start_ms", now - 24 * 60 * 60_000L)
            addProperty("end_ms", now + 24 * 60 * 60_000L)
            addProperty("limit", 10)
        }
        "calendar_delete_event" -> JsonObject().apply { addProperty("event_id", "0") }
        "notify_set_timer" -> JsonObject().apply { addProperty("seconds", 10); addProperty("message", "自检") }
        "notify_schedule_at" -> JsonObject().apply { addProperty("title", "自检提醒"); addProperty("at_ms", System.currentTimeMillis() + 60_000L) }
        "notify_cancel_scheduled" -> JsonObject().apply { addProperty("id", "0") }
        "file_export_session" -> JsonObject()
        "file_import_session" -> JsonObject()
        "calculate" -> JsonObject().apply { addProperty("code", "1+2+3") }
        "text_to_image" -> JsonObject().apply { addProperty("prompt", "a cat") }
        "vision_ocr_url" -> JsonObject().apply { addProperty("image_url", "https://httpbin.org/image/png"); addProperty("lang_hint", "zh") }
        "get_news_board" -> JsonObject().apply { addProperty("board", "weibo") }
        "get_daily_brief" -> JsonObject().apply { addProperty("source", "viki"); addProperty("format", "json") }
        "probe_public_api" -> JsonObject()
        "get_hotlist" -> JsonObject().apply { addProperty("platform", "weibo") }
        "public_translate" -> JsonObject().apply { addProperty("text", "hello"); addProperty("to", "zh") }
        "public_weather" -> JsonObject().apply { addProperty("query", "北京") }
        "public_exchange_rates" -> JsonObject().apply { addProperty("c", "CNY") }
        "ip_info" -> JsonObject().apply { addProperty("ip", "8.8.8.8") }
        "icp_lookup" -> JsonObject().apply { addProperty("domain", "example.com") }
        "phone_info" -> JsonObject().apply { addProperty("tel", "13800138000") }
        "qr_url" -> JsonObject().apply { addProperty("text", "https://example.com") }
        "get_session_stats" -> JsonObject().apply { addProperty("limit", 50) }
        "get_last_tool_errors" -> JsonObject().apply { addProperty("limit", 10) }
        "set_browse_policy" -> JsonObject().apply {
            add("allowlist", com.google.gson.JsonArray().apply { add("example.com") })
            add("denylist", com.google.gson.JsonArray().apply { add("localhost") })
        }
        "delete_memory" -> JsonObject().apply { addProperty("index", 0) }
        "update_memory" -> JsonObject().apply { addProperty("index", 0); addProperty("text", "工具自检") }
        else -> JsonObject()
    }
}

private suspend fun runToolWithAutoDenies(viewModel: ChatViewModel, name: String, args: JsonObject): String {
    val task = kotlinx.coroutines.coroutineScope {
        async {
            withTimeout(25_000L) { viewModel.runTool(name, args) }
        }
    }
    while (!task.isCompleted) {
        if (viewModel.pendingUserApproval != null) viewModel.respondToPendingUserApproval(false)
        if (viewModel.pendingDocumentRequest != null) viewModel.onDocumentPicked(null)
        delay(20)
    }
    return task.await()
}

private fun buildSelfCheckRow(tool: String, ms: Long, raw: String): ToolSelfCheckRow {
    return try {
        val obj = JsonParser.parseString(raw).asJsonObject
        val ok = obj.get("ok")?.asBoolean == true
        val code = obj.getAsJsonObject("error")?.get("code")?.asString
        val msg = obj.getAsJsonObject("error")?.get("message")?.asString.orEmpty()
        val status = if (ok) "PASS" else if (code in setOf("disabled", "user_denied", "permission_denied", "user_canceled")) "SKIP" else "FAIL"
        val detail = if (ok) "" else listOfNotNull(code, msg.takeIf { it.isNotBlank() }).joinToString(" ")
        ToolSelfCheckRow(tool = tool, status = status, ms = ms, detail = detail)
    } catch (_: Exception) {
        ToolSelfCheckRow(tool = tool, status = "FAIL", ms = ms, detail = "invalid_json")
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}
