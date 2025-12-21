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
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                ChatMessagesArea(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize().padding(bottom = 100.dp) 
                )
                
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    ChatInputArea(
                        viewModel = viewModel,
                        onSend = { 
                            viewModel.sendMessage(context, it)
                            viewModel.inputDraft = "" 
                        }
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(viewModel) { showSettings = false }
    }
    
    if (showProfile) {
        ProfileDialog(viewModel) { showProfile = false }
    }
}

@Composable
fun MuMuLogo(size: Dp, isAnimating: Boolean = false) {
    val infiniteTransition = rememberInfiniteTransition(label = "logo_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = Modifier.size(size).graphicsLayer { 
        if (isAnimating) {
            scaleX = pulse
            scaleY = pulse
        }
    }) {
        val center = size.toPx() / 2
        val radius = size.toPx() / 2
        
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(BrandPrimary.copy(alpha = 0.3f), Color.Transparent),
                center = Offset(center, center),
                radius = radius
            ),
            radius = radius
        )

        val path = Path().apply {
            val sides = 6
            val angleStep = (Math.PI * 2 / sides)
            val coreRadius = radius * 0.7f
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
            brush = Brush.sweepGradient(
                colors = listOf(LogoGradientStart, LogoGradientMid, LogoGradientEnd, LogoGradientStart),
                center = Offset(center, center)
            ),
            style = Fill
        )
        
        rotate(degrees = rotation, pivot = Offset(center, center)) {
             drawOval(
                color = Color.White.copy(alpha = 0.6f),
                topLeft = Offset(center - radius * 0.4f, center - radius * 0.15f),
                size = androidx.compose.ui.geometry.Size(radius * 0.8f, radius * 0.3f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
            )
             drawOval(
                color = Color.White.copy(alpha = 0.6f),
                topLeft = Offset(center - radius * 0.15f, center - radius * 0.4f),
                size = androidx.compose.ui.geometry.Size(radius * 0.3f, radius * 0.8f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    selectedModel: String,
    availableModels: List<String>,
    onMenuClick: () -> Unit,
    onProfileClick: () -> Unit,
    onModelSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayModelName = selectedModel.split("/").last()

    CenterAlignedTopAppBar(
        title = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = CircleShape,
                modifier = Modifier.clip(CircleShape).clickable { expanded = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = displayModelName,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.KeyboardArrowDown, 
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            DropdownMenu(
                expanded = expanded, 
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                availableModels.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.split("/").last(), fontSize = 14.sp) },
                        onClick = { 
                            onModelSelect(model)
                            expanded = false 
                        },
                        leadingIcon = {
                            if (model == selectedModel) {
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, "菜单", tint = MaterialTheme.colorScheme.onSurface)
            }
        },
        actions = {
            IconButton(onClick = onProfileClick) {
                Box(contentAlignment = Alignment.Center) {
                    MuMuLogo(size = 32.dp)
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent
        )
    )
}

@Composable
fun SidebarContent(viewModel: ChatViewModel, onSettingsClick: () -> Unit) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerTonalElevation = 0.dp,
        modifier = Modifier.width(320.dp)
    ) {
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
            
            Button(
                onClick = { viewModel.createNewChat() },
                modifier = Modifier.fillMaxWidth().height(52.dp).shadow(8.dp, RoundedCornerShape(26.dp), spotColor = BrandPrimary.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("开启新对话", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            
            Spacer(Modifier.height(32.dp))
            Text("最近历史", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.height(12.dp))
            
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(viewModel.sessions) { index, session ->
                    val isSelected = session.id == viewModel.currentSessionId
                    NavigationDrawerItem(
                        label = { Text(session.title, maxLines = 1, fontWeight = if(isSelected) FontWeight.Bold else FontWeight.Normal) },
                        selected = isSelected,
                        onClick = { viewModel.selectSession(session.id) },
                        icon = { Icon(Icons.Default.Email, null, modifier = Modifier.size(20.dp)) },
                        shape = RoundedCornerShape(12.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.secondary,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(16.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSettingsClick() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(16.dp))
                Text("设置与配置", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun ChatMessagesArea(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val messages = viewModel.currentMessages

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        itemsIndexed(messages) { index, message ->
            MessageItem(
                message = message, 
                onEdit = { viewModel.editMessage(index) }
            )
        }
    }
}

@Composable
fun MessageItem(message: ChatMessage, onEdit: () -> Unit) {
    val isUser = message.role == MessageRole.USER
    val clipboardManager = LocalClipboardManager.current
    val isDark = isSystemInDarkTheme()
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            MuMuLogo(size = 36.dp)
            Spacer(Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f, fill = false)) {
            if (!isUser) {
                Text(
                    "MuMu Intelligence", 
                    style = MaterialTheme.typography.labelMedium, 
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            if (isUser) {
                UserBubble(message, isDark)
            } else {
                AiBubble(message, isDark)
            }

            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(message.content)) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Share, 
                        contentDescription = "复制", 
                        modifier = Modifier.size(14.dp), 
                        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
                    )
                }
                
                if (isUser) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit, 
                            contentDescription = "编辑", 
                            modifier = Modifier.size(14.dp), 
                            tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UserBubble(message: ChatMessage, isDark: Boolean) {
    Column(horizontalAlignment = Alignment.End) {
        if (message.imageUrl != null) {
            AsyncImage(
                model = message.imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .sizeIn(maxWidth = 240.dp, maxHeight = 240.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
        }
        
        Surface(
            color = if(isDark) DarkBubbleUser else LightBubbleUser,
            shape = RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            SelectionContainer {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 24.sp,
                        fontSize = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun AiBubble(message: ChatMessage, isDark: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!message.reasoning.isNullOrBlank()) {
            val isThinkingFinished = message.content.isNotBlank()
            ThinkingBlock(reasoning = message.reasoning, isFinished = isThinkingFinished, isDark = isDark)
            Spacer(Modifier.height(12.dp))
        }

        if (message.content.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 28.sp,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Serif
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun ThinkingBlock(reasoning: String, isFinished: Boolean, isDark: Boolean) {
    var expanded by remember(isFinished) { mutableStateOf(!isFinished) } 
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f)
    val bgColor = if (isDark) ThinkingProcessBgDark else ThinkingProcessBgLight
    val accentColor = ThinkingAccent

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            .border(1.dp, accentColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val infiniteTransition = rememberInfiniteTransition()
                val alpha by if (!isFinished) infiniteTransition.animateFloat(
                    initialValue = 0.4f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse)
                ) else remember { mutableFloatStateOf(1f) }

                Icon(
                    Icons.AutoMirrored.Filled.List, 
                    contentDescription = null,
                    tint = accentColor.copy(alpha = alpha),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (isFinished) "已完成深度思考" else "深度思考中...",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = accentColor
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.graphicsLayer(rotationZ = rotation).size(18.dp),
                    tint = accentColor
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = accentColor.copy(alpha = 0.1f))
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer {
                        Text(
                            text = reasoning,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 22.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatInputArea(
    viewModel: ChatViewModel,
    onSend: (String) -> Unit
) {
    var text by remember(viewModel.inputDraft) { mutableStateOf(viewModel.inputDraft) }
    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        viewModel.selectedImageUri = uri
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Image Preview
        if (viewModel.selectedImageUri != null) {
            Box(modifier = Modifier.padding(bottom = 8.dp).size(80.dp)) {
                AsyncImage(
                    model = viewModel.selectedImageUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                IconButton(
                    onClick = { viewModel.selectedImageUri = null },
                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp).background(Color.Black.copy(0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(32.dp),
            shadowElevation = 16.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Multimodal Button
                if (viewModel.isVisionModel()) {
                    IconButton(
                        onClick = { photoLauncher.launch("image/*") },
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, "上传图片", tint = BrandPrimary)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if(viewModel.isVisionModel()) 8.dp else 20.dp, top = 14.dp, bottom = 14.dp)
                ) {
                    if (text.isEmpty()) {
                        Text("发送消息给 MuMu...", color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f))
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { 
                            text = it
                            viewModel.inputDraft = it
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5
                    )
                }
                
                val isEnabled = text.isNotBlank() || viewModel.selectedImageUri != null
                val bgColor by animateColorAsState(if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                val iconColor by animateColorAsState(if (isEnabled) Color.White else MaterialTheme.colorScheme.secondary)

                IconButton(
                    onClick = { 
                        onSend(text)
                        text = "" 
                    },
                    enabled = isEnabled,
                    modifier = Modifier
                        .padding(4.dp)
                        .size(40.dp)
                        .background(bgColor, CircleShape)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "发送",
                        tint = iconColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(viewModel.settings.baseUrl) }
    var key by remember { mutableStateOf(viewModel.settings.apiKey) }
    var selectedModels by remember { mutableStateOf(viewModel.settings.availableModels.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高级设置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Base URL") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API Key") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                
                HorizontalDivider()
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("模型库管理", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { viewModel.fetchAvailableModels() }) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("拉取列表")
                    }
                }
                
                if (viewModel.settings.fetchedModels.isNotEmpty()) {
                    viewModel.settings.fetchedModels.forEach { modelId ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedModels = if (selectedModels.contains(modelId)) selectedModels - modelId else selectedModels + modelId
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedModels.contains(modelId),
                                onCheckedChange = {
                                    selectedModels = if (it) selectedModels + modelId else selectedModels - modelId
                                }
                            )
                            Text(modelId, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                } else {
                    Text("暂未拉取或无模型，请点击拉取列表。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.updateSettings(viewModel.settings.copy(
                    baseUrl = url, 
                    apiKey = key,
                    availableModels = selectedModels.toList().sorted()
                ))
                onDismiss()
            }) { Text("应用并保存") }
        }
    )
}

@Composable
fun ProfileDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var prompt by remember { mutableStateOf(viewModel.settings.systemPrompt) }
    var newMemory by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 个性化") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = prompt, 
                    onValueChange = { prompt = it },
                    label = { Text("全局系统指令") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    shape = RoundedCornerShape(12.dp)
                )
                
                HorizontalDivider()
                
                Text("长期记忆库", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                viewModel.settings.memories.take(5).forEach { memory ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("• ", color = MaterialTheme.colorScheme.primary)
                        Text(memory, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
                
                OutlinedTextField(
                    value = newMemory, 
                    onValueChange = { newMemory = it },
                    placeholder = { Text("新增记忆...") },
                    trailingIcon = {
                        IconButton(onClick = { 
                            viewModel.addMemory(newMemory)
                            newMemory = "" 
                        }) {
                            Icon(Icons.Default.AddCircle, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.updateSettings(viewModel.settings.copy(systemPrompt = prompt))
                onDismiss()
            }) { Text("完成") }
        }
    )
}
