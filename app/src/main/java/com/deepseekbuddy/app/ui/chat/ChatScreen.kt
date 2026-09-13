package com.deepseekbuddy.app.ui.chat

import android.app.Application
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepseekbuddy.app.DeepSeekBuddyApp
import com.deepseekbuddy.app.agent.context.EmotionDetector
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.ui.common.AvatarView
import com.deepseekbuddy.app.ui.common.RoundTextField
import com.deepseekbuddy.app.util.MediaFiles
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: Long,
    onBack: () -> Unit,
    onOpenPersona: (personaId: Long, fromGroup: Boolean) -> Unit,
    onOpenGroupSettings: (conversationId: Long) -> Unit,
) {
    val context = LocalContext.current
    // 每个会话独立 key：否则 ViewModel 按类名复用，切换会话会串台
    val vm: ChatViewModel = viewModel(
        key = "chat_$conversationId",
        factory = ChatViewModelFactory(context.applicationContext as Application, conversationId)
    )
    val ui by vm.ui.collectAsState()
    val title by vm.title.collectAsState()
    val personaId by vm.personaId.collectAsState()
    val isGroup by vm.isGroup.collectAsState()
    val groupMembers by vm.groupMembers.collectAsState()
    val listState = rememberLazyListState()
    var showForwardDialog by remember { mutableStateOf(false) }
    val repo = (context.applicationContext as DeepSeekBuddyApp).container.repository
    val forwardTargets by repo.observeConversationList().collectAsState(initial = emptyList())

    // 思考过程显隐（设置页开关）
    val settings = remember { SettingsStore(context.applicationContext) }
    val showThinking by settings.showThinking.collectAsState(initial = false)
    val userAvatar by settings.userAvatar.collectAsState(initial = "")
    val assistantAvatar by vm.assistantAvatar.collectAsState()

    // 新消息或流式内容增长时自动滚到底部
    LaunchedEffect(ui.messages.size, ui.messages.lastOrNull()?.let { it as? UiMessage.Assistant }?.text) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    // 单聊：点头像/名字进角色主页
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = personaId != null) { personaId?.let { onOpenPersona(it, false) } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (personaId != null) {
                            AvatarView(assistantAvatar, 28.dp, fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(title.ifEmpty { "搭子" })
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isGroup) {
                        IconButton(onClick = { onOpenGroupSettings(conversationId) }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "群设置")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // 引用条
                ui.pendingQuote?.let { q ->
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "引用：${q.content}",
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = vm::cancelQuote) {
                                Icon(Icons.Default.Close, contentDescription = "取消引用", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if (ui.selectionMode) {
                    SelectionBar(
                        count = ui.selectedIds.size,
                        onForward = { showForwardDialog = true },
                        onExport = vm::exportSelected,
                        onFavorite = vm::favoriteSelected,
                        onCancel = vm::cancelSelection,
                    )
                } else {
                    InputBar(
                        streaming = ui.streaming,
                        onSend = vm::send,
                        onStop = vm::stop,
                        members = if (isGroup) groupMembers else emptyList(),
                    )
                }
            }
        },
    ) { padding ->
        // 工具操作确认弹窗（CONFIRM 风险级）
        ui.pendingConfirm?.let { p ->
            AlertDialog(
                onDismissRequest = { vm.respondConfirm(false) },
                title = { Text("搭子想执行操作") },
                text = { Text("${p.name}\n\n${p.argsSummary}\n\n是否允许？") },
                confirmButton = {
                    TextButton(onClick = { vm.respondConfirm(true) }) { Text("允许") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.respondConfirm(false) }) { Text("拒绝") }
                },
            )
        }

        // 消息长按菜单（整体消息框 + 右侧浮层；点外部任意处关闭）
        ui.menuTarget?.let { t ->
            Dialog(
                onDismissRequest = { vm.dismissMenu() },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(end = 16.dp)
                        .pointerInput(Unit) { detectTapGestures { vm.dismissMenu() } },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 8.dp,
                        tonalElevation = 2.dp,
                    ) {
                        Row(
                            Modifier.width(210.dp).padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            MenuItem("复制", vm::copyMessage, Modifier.weight(1f))
                            MenuItem("引用", vm::quoteMessage, Modifier.weight(1f))
                            if (t.isUser && !t.recalled && System.currentTimeMillis() - t.createdAt <= 180_000L) {
                                MenuItem("撤回", vm::recallMessage, Modifier.weight(1f))
                            }
                            MenuItem("删除", vm::deleteMessage, Modifier.weight(1f))
                            MenuItem("多选", vm::enterMultiSelect, Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 转发目标选择
        if (showForwardDialog) {
            AlertDialog(
                onDismissRequest = { showForwardDialog = false },
                title = { Text("转发到") },
                text = {
                    LazyColumn(Modifier.height(300.dp)) {
                        items(forwardTargets, key = { it.convId }) { c ->
                            TextButton(
                                onClick = {
                                    vm.forwardSelected(c.convId)
                                    showForwardDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(c.displayName) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showForwardDialog = false }) { Text("取消") }
                },
            )
        }
        if (ui.loading) {
            Text(
                "加载中…",
                modifier = Modifier.padding(padding).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }

        // 本会话聊天背景（… 菜单设置）：背景图 + 半透明遮罩保证可读性
        val repo = (context.applicationContext as DeepSeekBuddyApp).container.repository
        val conversation by repo.observeConversation(conversationId).collectAsState(initial = null)
        val bgPath = MediaFiles.pathFromRef(conversation?.bgRef ?: "")
        val bgBitmap = remember(bgPath) { bgPath?.let { MediaFiles.readBitmap(it) } }

        Box(Modifier.fillMaxSize().padding(padding)) {
            bgBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = if (bgBitmap != null) 0.85f else 1f))
            )

            // 情绪闪念：等待回复时背景呼吸光晕（焦虑→冷蓝，开心→暖黄，生气→红）
            val lastUserText = ui.messages.lastOrNull { it is UiMessage.User }?.let { (it as UiMessage.User).text }
            val lastGlowColor = lastUserText?.let { EmotionDetector.glowColor(EmotionDetector.detect(it)) }

            // 回复结束后光晕再停留 1.5s 淡出，保证可见（思考模式关闭时回复很快）
            var glowVisible by remember { mutableStateOf(false) }
            LaunchedEffect(ui.streaming, lastGlowColor) {
                if (ui.streaming && lastGlowColor != null) {
                    glowVisible = true
                } else if (glowVisible) {
                    delay(1500)
                    glowVisible = false
                }
            }
            if (glowVisible && lastGlowColor != null) {
                val glowTransition = rememberInfiniteTransition(label = "glow")
                val glowAlpha by glowTransition.animateFloat(
                    initialValue = 0.08f,
                    targetValue = 0.20f,
                    animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
                    label = "glowAlpha",
                )
                val glowScale by glowTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.15f,
                    animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
                    label = "glowScale",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = glowScale; scaleY = glowScale }
                        .background(
                            Brush.radialGradient(
                                listOf(lastGlowColor.copy(alpha = glowAlpha), Color.Transparent)
                            )
                        )
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.messages.isEmpty()) {
                    item { GreetingCard() }
                }
                items(ui.messages, key = { it.id }) { msg ->
                    val dbId = (msg as? UiMessage.User)?.dbId ?: (msg as? UiMessage.Assistant)?.dbId
                    MessageBubble(
                        msg = msg,
                        showThinking = showThinking,
                        selectionMode = ui.selectionMode,
                        selected = dbId != null && dbId in ui.selectedIds,
                        userAvatar = userAvatar,
                        assistantAvatar = assistantAvatar,
                        isGroup = isGroup,
                        personaId = personaId,
                        onOpenPersona = onOpenPersona,
                        onLongPress = { dbId?.let(vm::openMessageMenu) },
                        onClick = { if (ui.selectionMode) dbId?.let(vm::toggleSelect) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleWrapper(
    selected: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) { content() }
}

@Composable
private fun QuotePreview(content: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Text(
            content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onForward: () -> Unit,
    onExport: () -> Unit,
    onFavorite: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "已选 $count 条",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onForward, enabled = count > 0) { Text("转发") }
            TextButton(onClick = onExport, enabled = count > 0) { Text("导出") }
            TextButton(onClick = onFavorite, enabled = count > 0) { Text("收藏") }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

@Composable
private fun GreetingCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("嗨，我是你的搭子 👋", style = MaterialTheme.typography.titleMedium)
            Text(
                "试试让我做点什么：\n「帮我设一个 3 分钟后的提醒，提醒我喝水」",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: UiMessage,
    showThinking: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    userAvatar: String,
    assistantAvatar: String,
    isGroup: Boolean,
    personaId: Long?,
    onOpenPersona: (personaId: Long, fromGroup: Boolean) -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
) {
    when (msg) {
        is UiMessage.User -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BubbleWrapper(selected = selected, onLongPress = onLongPress, onClick = onClick) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            msg.quotedContent?.let { QuotePreview(it) }
                            if (msg.recalled) {
                                Text("（已撤回）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
                            } else {
                                Text(msg.text)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                AvatarView(userAvatar, 34.dp, fontSize = 16.sp)
            }
        }

        is UiMessage.Assistant -> {
            Row(Modifier.fillMaxWidth()) {
                // 点头像进角色主页：群聊进发言者主页（带私聊选项），单聊进角色主页
                AvatarView(
                    ref = msg.senderAvatar ?: assistantAvatar,
                    size = 34.dp,
                    fontSize = 16.sp,
                    onClick = {
                        if (isGroup) msg.senderPersonaId?.let { onOpenPersona(it, true) }
                        else personaId?.let { onOpenPersona(it, false) }
                    },
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    // 群聊：发言者名字（与头像上侧对齐，气泡下移）
                    msg.senderName?.let { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                        )
                    }
                    BubbleWrapper(selected = selected, onLongPress = onLongPress, onClick = onClick) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                msg.quotedContent?.let { QuotePreview(it) }
                                if (msg.reasoning.isNotEmpty()) {
                                    if (showThinking) {
                                        // 折叠的思考过程卡片
                                        var expanded by remember(msg.id) { mutableStateOf(false) }
                                        Surface(
                                            onClick = { expanded = !expanded },
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                                Text(
                                                    if (expanded) "🤔 思考过程（点击收起）" else "🤔 已思考（点击展开）",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                )
                                                if (expanded) {
                                                    Text(
                                                        msg.reasoning,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    )
                                                }
                                            }
                                        }
                                    } else if (msg.text.isEmpty() && msg.streaming) {
                                        // 隐藏思考过程：等待期间显示轻量占位
                                        Text(
                                            "🤔 思考中…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (msg.recalled) {
                                    Text("（已撤回）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
                                } else {
                                    Text(msg.text + if (msg.streaming) " ▌" else "")
                                }
                            }
                        }
                    }
                }
            }
        }

        is UiMessage.ToolCallCard -> {
            val resultColor = when (msg.success) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("🔧 ${msg.name}", style = MaterialTheme.typography.labelLarge)
                    Text(
                        msg.argsSummary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    msg.result?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = resultColor)
                    }
                }
            }
        }

        is UiMessage.Error -> {
            Text(
                msg.text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun InputBar(
    streaming: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    members: List<ChatViewModel.GroupMember>,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var showMentions by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (members.isNotEmpty()) {
                // @ 功能：全体成员 / 群成员
                Box {
                    IconButton(onClick = { showMentions = true }) {
                        Text("@", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = showMentions, onDismissRequest = { showMentions = false }) {
                        DropdownMenuItem(
                            text = { Text("全体成员") },
                            leadingIcon = { Text("👥", fontSize = 18.sp) },
                            onClick = {
                                text = text + "@全体成员 "
                                showMentions = false
                            },
                        )
                        members.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.name) },
                                leadingIcon = { AvatarView(m.avatarRef, 26.dp, fontSize = 15.sp) },
                                onClick = {
                                    text = text + "@${m.name} "
                                    showMentions = false
                                },
                            )
                        }
                    }
                }
            }
            RoundTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("和搭子说点什么…") },
                maxLines = 4,
                cornerRadius = 20.dp,
            )
            if (streaming) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Close, contentDescription = "停止")
                }
            } else {
                IconButton(onClick = { onSend(text); text = "" }, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}
