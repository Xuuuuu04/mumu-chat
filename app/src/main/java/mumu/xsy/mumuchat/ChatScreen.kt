package mumu.xsy.mumuchat

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import mumu.xsy.mumuchat.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    val context = LocalContext.current

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SidebarContent(viewModel, onSettingsClick = { showSettings = true })
        }
    ) {
        Scaffold(
            topBar = {
                ChatTopBar(
                    selectedModel = viewModel.settings.selectedModel,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onProfileClick = { showProfile = true },
                    onModelSelect = { model -> 
                        viewModel.updateSettings(viewModel.settings.copy(selectedModel = model))
                    },
                    availableModels = viewModel.settings.availableModels
                )
            },
            containerColor = Color.Transparent // Make Scaffold transparent to show background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background,
                                if (isSystemInDarkTheme()) Color(0xFF161B22) else Color(0xFFF0F2F5)
                            )
                        )
                    )
                    .padding(padding)
            ) {
                ChatMessagesArea(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize().padding(bottom = 100.dp) 
                )
                
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    ChatInputArea(viewModel = viewModel, onSend = { viewModel.sendMessage(context, it) })
                }
            }
        }
    }

    if (showSettings) SettingsDialog(viewModel) { showSettings = false }
    if (showProfile) ProfileDialog(viewModel) { showProfile = false }
}

@Composable
fun MuMuLogo(size: Dp, isAnimating: Boolean = false) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo_pulse")
    val pulse by infiniteTransition.animateFloat(0.95f, 1.05f, infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")
    val rotation by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart), label = "rotation")

    Canvas(modifier = Modifier.size(size).graphicsLayer { if (isAnimating) { scaleX = pulse; scaleY = pulse } }) {
        val center = size.toPx() / 2
        val radius = size.toPx() / 2
        
        // 1. Outer Glow (Soft)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to BrandPrimary.copy(alpha = 0.4f),
                0.6f to BrandSecondary.copy(alpha = 0.1f),
                1.0f to Color.Transparent,
                center = Offset(center, center),
                radius = radius
            ),
            radius = radius
        )

        val path = Path().apply {
            val sides = 6
            val angleStep = (Math.PI * 2 / sides)
            val coreRadius = radius * 0.65f // Slightly smaller for better proportions
            for (i in 0 until sides) {
                val angle = i * angleStep - (Math.PI / 2)
                val x = center + coreRadius * cos(angle).toFloat()
                val y = center + coreRadius * sin(angle).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }

        drawPath(
            path = path,
            brush = Brush.linearGradient(
                listOf(LogoGradientStart, LogoGradientMid, LogoGradientEnd),
                start = Offset(0f, 0f),
                end = Offset(size.toPx(), size.toPx())
            ),
            style = Fill
        )
        
        // 3. Highlight Sparkle
        drawCircle(
            color = Color.White.copy(alpha = 0.2f),
            radius = radius * 0.2f,
            center = Offset(center - radius * 0.2f, center - radius * 0.2f)
        )
        
        rotate(degrees = rotation, pivot = Offset(center, center)) {
             drawOval(color = Color.White.copy(0.6f), topLeft = Offset(center - radius * 0.4f, center - radius * 0.15f), size = androidx.compose.ui.geometry.Size(radius * 0.8f, radius * 0.3f), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
             drawOval(color = Color.White.copy(0.6f), topLeft = Offset(center - radius * 0.15f, center - radius * 0.4f), size = androidx.compose.ui.geometry.Size(radius * 0.3f, radius * 0.8f), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(selectedModel: String, availableModels: List<String>, onMenuClick: () -> Unit, onProfileClick: () -> Unit, onModelSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        title = {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f), shape = CircleShape, modifier = Modifier.clip(CircleShape).clickable { expanded = true }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(text = selectedModel.split("/").last(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                availableModels.forEach { model ->
                    DropdownMenuItem(text = { Text(model.split("/").last(), fontSize = 14.sp) }, onClick = { onModelSelect(model); expanded = false }, leadingIcon = { if (model == selectedModel) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) })
                }
            }
        },
        navigationIcon = { IconButton(onClick = onMenuClick) { Icon(Icons.Default.Menu, "菜单") } },
        actions = { IconButton(onClick = onProfileClick) { MuMuLogo(size = 32.dp) } },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
    )
}

@Composable
fun SidebarContent(viewModel: ChatViewModel, onSettingsClick: () -> Unit) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background, drawerTonalElevation = 0.dp, modifier = Modifier.width(320.dp)) {
        Column(modifier = Modifier.fillMaxHeight().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MuMuLogo(size = 40.dp, isAnimating = true)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("MuMu Chat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("AI Assistant", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.height(32.dp))
            Button(onClick = { viewModel.createNewChat() }, modifier = Modifier.fillMaxWidth().height(52.dp).shadow(8.dp, RoundedCornerShape(26.dp), spotColor = BrandPrimary.copy(0.2f)), shape = RoundedCornerShape(26.dp)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("开启新对话", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.height(32.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(viewModel.sessions) { _, session ->
                    val isSelected = session.id == viewModel.currentSessionId
                    NavigationDrawerItem(label = { Text(session.title, maxLines = 1) }, selected = isSelected, onClick = { viewModel.selectSession(session.id) }, icon = { Icon(Icons.Default.Email, null) }, shape = RoundedCornerShape(12.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSettingsClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(16.dp)); Text("设置与配置")
            }
        }
    }
}

@Composable
fun ChatMessagesArea(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel.currentMessages.size) { if (viewModel.currentMessages.isNotEmpty()) listState.animateScrollToItem(viewModel.currentMessages.size - 1) }
    LazyColumn(state = listState, modifier = modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        itemsIndexed(viewModel.currentMessages) { index, message ->
            MessageItem(message = message, onEdit = { viewModel.editMessage(index) })
        }
    }
}

@Composable
fun MessageItem(message: ChatMessage, onEdit: () -> Unit) {
    val isUser = message.role == MessageRole.USER
    val clipboard = LocalClipboardManager.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) { MuMuLogo(size = 36.dp); Spacer(Modifier.width(16.dp)) }
        Column(modifier = Modifier.weight(1f, fill = false)) {
            if (isUser) UserBubble(message) else AiBubble(message)
            Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary) }
                if (isUser) IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary) }
            }
        }
    }
}

@Composable
fun UserBubble(message: ChatMessage) {
    Column(horizontalAlignment = Alignment.End) {
        if (message.imageUrl != null) AsyncImage(model = message.imageUrl, contentDescription = null, modifier = Modifier.padding(bottom = 8.dp).sizeIn(maxWidth = 240.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
        Surface(color = if(isSystemInDarkTheme()) DarkBubbleUser else LightBubbleUser, shape = RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            SelectionContainer { Text(text = message.content, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
fun AiBubble(message: ChatMessage) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (message.steps.isNotEmpty()) {
            TaskFlowContainer(steps = message.steps)
            Spacer(Modifier.height(12.dp))
        }
        if (message.content.isNotBlank()) {
            SelectionContainer { Text(text = message.content, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp, fontFamily = FontFamily.Serif)) }
        }
    }
}

@Composable
fun TaskFlowContainer(steps: List<ChatStep>) {
    var allExpanded by remember { mutableStateOf(true) }
    
    Column(modifier = Modifier.fillMaxWidth().border(1.dp, BrandPrimary.copy(0.1f), RoundedCornerShape(12.dp)).background(if(isSystemInDarkTheme()) ThinkingProcessBgDark else ThinkingProcessBgLight, RoundedCornerShape(12.dp))) {
        Row(modifier = Modifier.fillMaxWidth().clickable { allExpanded = !allExpanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = BrandPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("推理链与执行历史", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = BrandPrimary)
            Spacer(Modifier.weight(1f))
            Text("${steps.size} 步", style = MaterialTheme.typography.labelSmall, color = BrandPrimary.copy(0.7f))
            Icon(if(allExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp), tint = BrandPrimary)
        }
        
        AnimatedVisibility(visible = allExpanded) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                steps.forEach { step -> StepItem(step) }
            }
        }
    }
}

@Composable
fun StepItem(step: ChatStep) {
    var expanded by remember { mutableStateOf(false) }
    val isThinking = step.type == StepType.THINKING
    
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val infinite = rememberInfiniteTransition()
            val alpha by if(!step.isFinished) infinite.animateFloat(0.4f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse)) else remember { mutableFloatStateOf(1f) }
            
            Icon(if(isThinking) Icons.Default.Face else Icons.Default.Build, null, tint = if(isThinking) BrandSecondary else ThinkingAccent, modifier = Modifier.size(16.dp).graphicsLayer { this.alpha = alpha })
            Spacer(Modifier.width(8.dp))
            Text(if(isThinking) "思考内容" else "调用工具: ${step.toolName}", style = MaterialTheme.typography.labelSmall, color = if(isThinking) BrandSecondary else ThinkingAccent)
            Spacer(Modifier.weight(1f))
            if(step.content.isNotBlank()) Icon(if(expanded) Icons.Default.KeyboardArrowUp else Icons.Default.Add, null, modifier = Modifier.size(12.dp), tint = Color.Gray)
        }
        AnimatedVisibility(visible = expanded && step.content.isNotBlank()) {
            SelectionContainer {
                Text(text = step.content, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.7f)), modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
            }
        }
    }
}

@Composable
fun ChatInputArea(viewModel: ChatViewModel, onSend: (String) -> Unit) {
    var text by remember(viewModel.inputDraft) { mutableStateOf(viewModel.inputDraft) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { viewModel.selectedImageUri = it }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (viewModel.selectedImageUri != null) Box(modifier = Modifier.padding(bottom = 8.dp).size(80.dp)) {
            AsyncImage(model = viewModel.selectedImageUri, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            IconButton(onClick = { viewModel.selectedImageUri = null }, modifier = Modifier.align(Alignment.TopEnd).size(20.dp).background(Color.Black.copy(0.5f), CircleShape)) { Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(32.dp), shadowElevation = 16.dp, modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                if (viewModel.isVisionModel()) IconButton(onClick = { photoLauncher.launch("image/*") }) { Icon(Icons.Default.Add, null, tint = BrandPrimary) }
                Box(modifier = Modifier.weight(1f).padding(start = if(viewModel.isVisionModel()) 8.dp else 20.dp, top = 14.dp, bottom = 14.dp)) {
                    if (text.isEmpty()) Text("发送消息...", color = MaterialTheme.colorScheme.secondary.copy(0.6f))
                    BasicTextField(value = text, onValueChange = { text = it; viewModel.inputDraft = it }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 5)
                }
                val isEnabled = text.isNotBlank() || viewModel.selectedImageUri != null
                IconButton(onClick = { onSend(text); text = "" }, enabled = isEnabled, modifier = Modifier.padding(4.dp).size(40.dp).background(if(isEnabled) BrandPrimary else MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = if(isEnabled) Color.White else MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(viewModel.settings.baseUrl) }
    var key by remember { mutableStateOf(viewModel.settings.apiKey) }
    var exaKey by remember { mutableStateOf(viewModel.settings.exaApiKey) }
    var selectedModels by remember { mutableStateOf(viewModel.settings.availableModels.toSet()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("高级设置") }, text = {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("硅基流动 Base URL") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("硅基流动 API Key") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = exaKey, onValueChange = { exaKey = it }, label = { Text("Exa Search API Key") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("模型库管理", style = MaterialTheme.typography.titleSmall); TextButton(onClick = { viewModel.fetchAvailableModels() }) { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("拉取列表") }
            }
            if (viewModel.settings.fetchedModels.isNotEmpty()) {
                viewModel.settings.fetchedModels.forEach { modelId ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { selectedModels = if (selectedModels.contains(modelId)) selectedModels - modelId else selectedModels + modelId }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selectedModels.contains(modelId), onCheckedChange = { selectedModels = if (it) selectedModels + modelId else selectedModels - modelId })
                        Text(modelId, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }, confirmButton = { Button(onClick = { viewModel.updateSettings(viewModel.settings.copy(baseUrl = url, apiKey = key, exaApiKey = exaKey, availableModels = selectedModels.toList().sorted())); onDismiss() }) { Text("应用并保存") } })
}

@Composable
fun ProfileDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var prompt by remember { mutableStateOf(viewModel.settings.systemPrompt) }
    var newMemory by remember { mutableStateOf("") }
    val memories = viewModel.settings.memories
    AlertDialog(onDismissRequest = onDismiss, title = { Text("AI 个性化与记忆") }, text = {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("全局系统人设", style = MaterialTheme.typography.labelLarge, color = BrandPrimary)
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(12.dp))
            HorizontalDivider()
            Text("长期记忆", style = MaterialTheme.typography.labelLarge, color = BrandPrimary)
            if (memories.isEmpty()) Text("暂无记忆", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            memories.forEachIndexed { i, m -> MemoryItem(text = m, onDelete = { viewModel.deleteMemory(i) }, onUpdate = { viewModel.updateMemory(i, it) }) }
            OutlinedTextField(value = newMemory, onValueChange = { newMemory = it }, placeholder = { Text("手动添加...") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), trailingIcon = { IconButton(onClick = { viewModel.addMemory(newMemory); newMemory = "" }, enabled = newMemory.isNotBlank()) { Icon(Icons.Default.AddCircle, null, tint = if(newMemory.isNotBlank()) BrandPrimary else Color.Gray) } })
        }
    }, confirmButton = { Button(onClick = { viewModel.updateSettings(viewModel.settings.copy(systemPrompt = prompt)); onDismiss() }) { Text("完成") } })
}

@Composable
fun MemoryItem(text: String, onDelete: () -> Unit, onUpdate: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(text) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (editing) {
                BasicTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth().padding(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { editing = false }) { Text("取消") }
                    TextButton(onClick = { onUpdate(editText); editing = false }) { Text("保存") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "• $text", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(8.dp))
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = BrandPrimary) }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = Color.Red.copy(0.7f)) }
                }
            }
        }
    }
}