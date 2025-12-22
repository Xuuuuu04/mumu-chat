package mumu.xsy.mumuchat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import android.webkit.WebView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.launch
import mumu.xsy.mumuchat.ui.theme.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SidebarContent(viewModel, onSettingsClick = { showSettings = true })
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            MeshBackground(isDark)

            Scaffold(
                topBar = {
                    ChatTopBar(
                        selectedModel = viewModel.settings.selectedModel,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onProfileClick = { showProfile = true },
                        onModelSelect = { model -> viewModel.updateSettings(viewModel.settings.copy(selectedModel = model)) },
                        availableModels = viewModel.settings.availableModels
                    )
                },
                containerColor = Color.Transparent,
                bottomBar = {
                    ChatInputDock(viewModel = viewModel, onSend = { viewModel.sendMessage(context, it) })
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

    if (showSettings) SettingsDialog(viewModel) { showSettings = false }
    if (showProfile) ProfileDialog(viewModel) { showProfile = false }
    if (previewHtmlContent != null) {
        HtmlPreviewDialog(htmlContent = previewHtmlContent!!) { previewHtmlContent = null }
    }
}

@Composable
fun HtmlPreviewDialog(htmlContent: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null, tint = Color.Black)
                    }
                }
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
                        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "utf-8", null)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun MeshBackground(isDark: Boolean) {
    val transition = rememberInfiniteTransition(label = "mesh")
    val animOffset by transition.animateFloat(
        0f, 100f,
        infiniteRepeatable(tween(10000, easing = LinearEasing), RepeatMode.Reverse),
        label = "offset"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(color = if (isDark) DarkBgBase else LightBgBase)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(BrandPrimary.copy(alpha = if(isDark) 0.15f else 0.08f), Color.Transparent),
                center = Offset(size.width * 0.2f + animOffset, size.height * 0.2f),
                radius = size.width * 0.8f
            ),
            radius = size.width * 0.8f
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(BrandSecondary.copy(alpha = if(isDark) 0.12f else 0.06f), Color.Transparent),
                center = Offset(size.width * 0.8f - animOffset, size.height * 0.7f),
                radius = size.width * 0.7f
            ),
            radius = size.width * 0.7f
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(selectedModel: String, availableModels: List<String>, onMenuClick: () -> Unit, onProfileClick: () -> Unit, onModelSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        title = {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { expanded = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically, 
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    val displayName = selectedModel.split("/").last()
                    Text(
                        text = displayName, 
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        null, 
                        modifier = Modifier.size(16.dp), 
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // 优化后的下拉菜单
            DropdownMenu(
                expanded = expanded, 
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .widthIn(min = 200.dp)
            ) {
                availableModels.forEach { model ->
                    val isSelected = model == selectedModel
                    DropdownMenuItem(
                        text = { 
                            Text(
                                text = model.split("/").last(),
                                style = if (isSelected) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold) 
                                        else MaterialTheme.typography.labelLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            ) 
                        },
                        onClick = { 
                            onModelSelect(model)
                            expanded = false 
                        },
                        leadingIcon = {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                    )
                }
            }
        },
        navigationIcon = { IconButton(onClick = onMenuClick) { Icon(Icons.Default.Menu, "菜单", tint = MaterialTheme.colorScheme.onSurface) } },
        actions = { IconButton(onClick = onProfileClick) { MuMuLogo(size = 32.dp) } },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
    )
}

@Composable
fun SidebarContent(viewModel: ChatViewModel, onSettingsClick: () -> Unit) {
    var showRenameDialog by remember { mutableStateOf<String?>(null) } // SessionId
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newFolderName by remember { mutableStateOf("") }

    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background, drawerTonalElevation = 0.dp, modifier = Modifier.width(320.dp)) {
        Column(modifier = Modifier.fillMaxHeight().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MuMuLogo(size = 44.dp, isAnimating = true)
                Spacer(Modifier.width(16.dp))
                Column { Text("MuMu Chat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text("Super Agent v2.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.height(32.dp))
            Button(onClick = { viewModel.createNewChat() }, modifier = Modifier.fillMaxWidth().height(52.dp).shadow(12.dp, RoundedCornerShape(26.dp), spotColor = BrandPrimary.copy(0.3f)), shape = RoundedCornerShape(26.dp)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("新对话", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("Folders", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showNewFolderDialog = true }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                }
            }

            // 会话列表（按文件夹展示）
            LazyColumn(modifier = Modifier.weight(1f)) {
                // 1. 无文件夹的会话
                val ungrouped = viewModel.sessions.filter { it.folder == null }
                itemsIndexed(ungrouped) { _, session ->
                    SessionItem(session, viewModel, onRename = { 
                        showRenameDialog = session.id
                        newTitle = session.title 
                    })
                }

                // 2. 按文件夹分类
                viewModel.settings.folders.forEach { folderName ->
                    val folderSessions = viewModel.sessions.filter { it.folder == folderName }
                    item {
                        var folderExpanded by remember { mutableStateOf(false) }
                        var showFolderMenu by remember { mutableStateOf(false) }
                        
                        Box {
                            Row(modifier = Modifier.fillMaxWidth().clickable { folderExpanded = !folderExpanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if(folderExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Icon(Icons.Default.List, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(folderName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.weight(1f))
                                Text("${folderSessions.size}", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(14.dp).clickable { showFolderMenu = true }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = showFolderMenu, onDismissRequest = { showFolderMenu = false }) {
                                DropdownMenuItem(text = { Text("删除文件夹") }, onClick = { viewModel.deleteFolder(folderName); showFolderMenu = false }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) })
                            }
                        }
                        
                        if (folderExpanded) {
                            Column(modifier = Modifier.padding(start = 16.dp)) {
                                folderSessions.forEach { session ->
                                    SessionItem(session, viewModel, onRename = { 
                                        showRenameDialog = session.id
                                        newTitle = session.title 
                                    })
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outline)
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onSettingsClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(16.dp)); Text("模型与Key配置")
            }
        }
    }

    if (showRenameDialog != null) {
        AlertDialog(onDismissRequest = { showRenameDialog = null }, title = { Text("重命名会话") }, text = {
            OutlinedTextField(value = newTitle, onValueChange = { newTitle = it }, modifier = Modifier.fillMaxWidth())
        }, confirmButton = {
            Button(onClick = { viewModel.renameSession(showRenameDialog!!, newTitle); showRenameDialog = null }) { Text("保存") }
        })
    }
    
    if (showNewFolderDialog) {
        AlertDialog(onDismissRequest = { showNewFolderDialog = false }, title = { Text("新建文件夹") }, text = {
            OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, placeholder = { Text("文件夹名称") }, modifier = Modifier.fillMaxWidth())
        }, confirmButton = {
            Button(onClick = { viewModel.createFolder(newFolderName); newFolderName = ""; showNewFolderDialog = false }) { Text("创建") }
        })
    }
}

@Composable
fun SessionItem(session: ChatSession, viewModel: ChatViewModel, onRename: () -> Unit) {
    val isSelected = session.id == viewModel.currentSessionId
    var showMenu by remember { mutableStateOf(false) }

    Box {
        NavigationDrawerItem(
            label = { Text(session.title, maxLines = 1) },
            selected = isSelected,
            onClick = { viewModel.selectSession(session.id) },
            icon = { Icon(if(isSelected) Icons.Default.Email else Icons.Default.Email, null) },
            badge = {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(16.dp))
                }
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(text = { Text("重命名") }, onClick = { onRename(); showMenu = false }, leadingIcon = { Icon(Icons.Default.Edit, null) })
            DropdownMenuItem(text = { Text("删除") }, onClick = { viewModel.deleteSession(session.id); showMenu = false }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red) })
            
            HorizontalDivider()
            DropdownMenuItem(text = { Text("移出文件夹") }, onClick = { viewModel.moveSessionToFolder(session.id, null); showMenu = false })
            viewModel.settings.folders.forEach { folder ->
                DropdownMenuItem(text = { Text("移至: $folder") }, onClick = { viewModel.moveSessionToFolder(session.id, folder); showMenu = false })
            }
        }
    }
}

@Composable
fun ChatMessagesArea(viewModel: ChatViewModel, modifier: Modifier = Modifier, onPreviewHtml: (String) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.currentMessages.size) { 
        if (viewModel.currentMessages.isNotEmpty()) listState.animateScrollToItem(viewModel.currentMessages.size - 1) 
    }
    LazyColumn(
        state = listState, 
        modifier = modifier, 
        contentPadding = PaddingValues(bottom = 24.dp, start = 16.dp, end = 16.dp), 
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        itemsIndexed(viewModel.currentMessages) { index, message ->
            MessageItem(message = message, onEdit = { viewModel.editMessage(index) }, onPreviewHtml = onPreviewHtml)
        }
    }
}

@Composable
fun MessageItem(message: ChatMessage, onEdit: () -> Unit, onPreviewHtml: (String) -> Unit) {
    val isUser = message.role == MessageRole.USER
    val isDark = isSystemInDarkTheme()
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (!isUser) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                MuMuLogo(size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Text("MuMu Intelligence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isUser) UserBubble(message, isDark, onEdit) else AiBubble(message, isDark, onPreviewHtml)
    }
}

@Composable
fun UserBubble(message: ChatMessage, isDark: Boolean, onEdit: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(horizontalAlignment = Alignment.End) {
        if (message.imageUrl != null) {
            AsyncImage(model = message.imageUrl, contentDescription = null, modifier = Modifier.padding(bottom = 8.dp).sizeIn(maxWidth = 260.dp, maxHeight = 260.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
        }
        Surface(
            color = if(isDark) UserBubbleDark else UserBubbleLight,
            shape = RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SelectionContainer { Text(text = message.content, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)) }
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp).clickable { onEdit() }, tint = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp).clickable { clipboard.setText(AnnotatedString(message.content)) }, tint = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                }
            }
        }
    }
}

@Composable
fun AiBubble(message: ChatMessage, isDark: Boolean, onPreviewHtml: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val htmlBlock = remember(message.content) { 
        "```html\\s+(.*?)\\s+```".toRegex(RegexOption.DOT_MATCHES_ALL).find(message.content)?.groupValues?.get(1)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (message.steps.isNotEmpty()) {
            TaskFlowContainer(steps = message.steps)
            Spacer(Modifier.height(16.dp))
        }
        if (message.imageUrl != null) {
            AsyncImage(model = message.imageUrl, contentDescription = null, modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)), contentScale = ContentScale.Fit)
        }
        if (message.content.isNotBlank()) {
            SelectionContainer {
                // 深度定制 Markdown 样式
                Markdown(
                    content = message.content,
                    colors = markdownColor(
                        text = MaterialTheme.colorScheme.onSurface,
                        codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    ),
                    typography = markdownTypography(
                        h1 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = BrandPrimary),
                        h2 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = BrandPrimary),
                        h3 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold, color = BrandPrimary),
                        text = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                        code = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                )
            }
            
            if (htmlBlock != null) {
                Button(
                    onClick = { onPreviewHtml(htmlBlock) },
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary.copy(alpha = 0.1f), contentColor = BrandPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("预览 HTML / 运行代码", style = MaterialTheme.typography.labelMedium)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                )
            }
        }
    }
}

@Composable
fun TaskFlowContainer(steps: List<ChatStep>) {
    var allExpanded by remember { mutableStateOf(false) } 
    val listState = rememberLazyListState()
    
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().clickable { allExpanded = !allExpanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = BrandAccent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp)); Text("任务链执行中", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = BrandAccent)
                Spacer(Modifier.weight(1f)); Icon(if(allExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = allExpanded) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).heightIn(max = 240.dp)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(steps) { _, step -> StepItem(step) }
                    }
                }
            }
        }
    }
}

@Composable
fun StepItem(step: ChatStep) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            val alpha by if(!step.isFinished) rememberInfiniteTransition().animateFloat(0.4f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse)) else remember { mutableFloatStateOf(1f) }
            Icon(if(step.type == StepType.THINKING) Icons.Default.Face else Icons.Default.Settings, null, tint = if(step.type == StepType.THINKING) BrandSecondary else ThinkingAccent, modifier = Modifier.size(14.dp).graphicsLayer { this.alpha = alpha })
            Spacer(Modifier.width(8.dp)); Text(if(step.type == StepType.THINKING) "思考中..." else "Action: ${step.toolName}", style = MaterialTheme.typography.labelSmall, color = if(step.type == StepType.THINKING) BrandSecondary else ThinkingAccent)
            Spacer(Modifier.weight(1f)); if(step.content.isNotBlank()) Icon(if(expanded) Icons.Default.KeyboardArrowUp else Icons.Default.Add, null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = expanded && step.content.isNotBlank()) {
            Text(text = step.content, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.8f)), modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 8.dp))
        }
    }
}

@Composable
fun ChatInputDock(viewModel: ChatViewModel, onSend: (String) -> Unit) {
    var text by remember(viewModel.inputDraft) { mutableStateOf(viewModel.inputDraft) }
    val context = LocalContext.current
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { viewModel.selectedImageUri = it }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { try { viewModel.inputDraft += "\n[文件解析]:\n" + BufferedReader(InputStreamReader(context.contentResolver.openInputStream(it))).readText() } catch (e: Exception) {} }
    }

    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(modifier = Modifier.padding(12.dp)) {
                if (viewModel.selectedImageUri != null) {
                    Box(modifier = Modifier.padding(bottom = 12.dp).size(70.dp)) {
                        AsyncImage(model = viewModel.selectedImageUri, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        IconButton(onClick = { viewModel.selectedImageUri = null }, modifier = Modifier.align(Alignment.TopEnd).size(18.dp).background(Color.Black.copy(0.6f), CircleShape)) { Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(10.dp)) }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) }
                                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                                DropdownMenuItem(text = { Text("图片") }, onClick = { photoLauncher.launch("image/*"); showMenu = false }, leadingIcon = { Icon(Icons.Default.Add, null) })
                                                DropdownMenuItem(text = { Text("文档") }, onClick = { fileLauncher.launch("*/*"); showMenu = false }, leadingIcon = { Icon(Icons.Default.Info, null) })
                                            }
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            if (text.isEmpty()) Text("发个消息给 MuMu...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f))
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it; viewModel.inputDraft = it },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 6
                            )
                        }
                    }
                    val isEnabled = text.isNotBlank() || viewModel.selectedImageUri != null
                    IconButton(onClick = { onSend(text); text = "" }, enabled = isEnabled, modifier = Modifier.size(44.dp).background(if (isEnabled) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = if (isEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun MuMuLogo(size: Dp, isAnimating: Boolean = false) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo")
    val rotation by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "rot")
    Canvas(modifier = Modifier.size(size)) {
        val center = Offset(size.toPx() / 2, size.toPx() / 2)
        val radius = size.toPx() / 2
        drawCircle(brush = Brush.radialGradient(listOf(BrandPrimary.copy(0.4f), Color.Transparent), center = center, radius = radius), radius = radius)
        val path = Path().apply {
            val s = 6; val r = radius * 0.7f
            for (i in 0 until s) {
                val a = i * (Math.PI * 2 / s) - (Math.PI / 2)
                val x = center.x + r * cos(a).toFloat(); val y = center.y + r * sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path, brush = Brush.linearGradient(listOf(LogoGradientStart, LogoGradientEnd), start = Offset.Zero, end = Offset(size.toPx(), size.toPx())))
        rotate(rotation, pivot = center) { drawCircle(Color.White.copy(0.3f), radius * 0.15f, center = Offset(center.x + radius * 0.4f, center.y)) }
    }
}

@Composable
fun SettingsDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(viewModel.settings.baseUrl) }
    var key by remember { mutableStateOf(viewModel.settings.apiKey) }
    var exaKey by remember { mutableStateOf(viewModel.settings.exaApiKey) }
    var newModelName by remember { mutableStateOf("") }
    
    // State for selected new models
    var selectedNewModels by remember { mutableStateOf<Set<String>>(emptySet()) }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("模型与Key配置") }, text = {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Base URL") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API Key") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = exaKey, onValueChange = { exaKey = it }, label = { Text("Exa Search Key") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            
            Text("模型管理", style = MaterialTheme.typography.titleSmall)
            
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newModelName, 
                    onValueChange = { newModelName = it }, 
                    label = { Text("手动添加模型") },
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                )
                IconButton(onClick = { viewModel.addModel(newModelName); newModelName = "" }, enabled = newModelName.isNotBlank()) {
                    Icon(Icons.Default.AddCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("可用模型列表", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { viewModel.fetchAvailableModels() }) { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("拉取更新") }
            }

            viewModel.settings.availableModels.forEach { modelId ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(modelId, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.removeModel(modelId) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, null, tint = Color.Red.copy(0.6f), modifier = Modifier.size(16.dp))
                    }
                }
            }

            val newModels = viewModel.settings.fetchedModels.filter { !viewModel.settings.availableModels.contains(it) }
            if (newModels.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("发现新模型 (${newModels.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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

                newModels.forEach { modelId ->
                    val isChecked = selectedNewModels.contains(modelId)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { 
                            selectedNewModels = if (isChecked) selectedNewModels - modelId else selectedNewModels + modelId 
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { 
                                selectedNewModels = if (it) selectedNewModels + modelId else selectedNewModels - modelId 
                            }
                        )
                        Text(modelId, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }, confirmButton = { Button(onClick = { viewModel.updateSettings(viewModel.settings.copy(baseUrl = url, apiKey = key, exaApiKey = exaKey)); onDismiss() }) { Text("保存") } })
}

@Composable
fun ProfileDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var prompt by remember { mutableStateOf(viewModel.settings.systemPrompt) }
    var newMemory by remember { mutableStateOf("") }
    val memories = viewModel.settings.memories
    AlertDialog(onDismissRequest = onDismiss, title = { Text("AI 个性化与记忆") }, text = {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("系统人设", style = MaterialTheme.typography.labelLarge, color = BrandPrimary)
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(12.dp))
            HorizontalDivider(); Text("长期记忆", style = MaterialTheme.typography.labelLarge, color = BrandPrimary)
            if (memories.isEmpty()) Text("暂无记忆", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            memories.forEachIndexed { i, m -> MemoryItem(text = m, onDelete = { viewModel.deleteMemory(i) }, onUpdate = { viewModel.updateMemory(i, it) }) }
            OutlinedTextField(value = newMemory, onValueChange = { newMemory = it }, placeholder = { Text("添加记忆...") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), trailingIcon = { IconButton(onClick = { viewModel.addMemory(newMemory); newMemory = "" }, enabled = newMemory.isNotBlank()) { Icon(Icons.Default.AddCircle, null, tint = if(newMemory.isNotBlank()) BrandPrimary else Color.Gray) } })
        }
    }, confirmButton = { Button(onClick = { viewModel.updateSettings(viewModel.settings.copy(systemPrompt = prompt)); onDismiss() }) { Text("完成") } })
}

@Composable
fun MemoryItem(text: String, onDelete: () -> Unit, onUpdate: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(text) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (editing) {
                BasicTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth().padding(8.dp), textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { editing = false }) { Text("取消") }
                    TextButton(onClick = { onUpdate(editText); editing = false }) { Text("保存") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "• $text", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(8.dp))
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp), tint = BrandPrimary) }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = Color.Red.copy(0.6f)) }
                }
            }
        }
    }
}