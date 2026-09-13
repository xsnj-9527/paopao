package com.deepseekbuddy.app.ui.personas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekbuddy.agent.ChatMessage
import com.deepseekbuddy.agent.llm.DeepSeekClient
import com.deepseekbuddy.app.agent.persona.PersonaTemplates
import com.deepseekbuddy.app.data.PersonaRepository
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.ui.common.AvatarCropperScreen
import com.deepseekbuddy.app.ui.common.AvatarView
import com.deepseekbuddy.app.ui.common.RoundTextField
import com.deepseekbuddy.app.util.MediaFiles
import kotlinx.coroutines.launch

/**
 * 角色修改页（角色主页 → 修改）：原 ⋮ 功能迁移 + 主页背景 + 签名重新生成 + 个性标签。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonaEditScreen(
    personaId: Long,
    repo: PersonaRepository,
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val persona by repo.observePersona(personaId).collectAsState(initial = null)

    // 会话 id（聊天背景用）
    var conversationId by remember { mutableStateOf<Long?>(null) }
    val conversation by repo.observeConversation(conversationId ?: -1L).collectAsState(initial = null)
    LaunchedEffect(persona?.id) {
        if (conversationId == null) {
            persona?.let { p -> repo.getConversationByPersona(p.id)?.let { conversationId = it.id } }
        }
    }

    // 表单
    var name by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf("") }
    var appearance by remember { mutableStateOf("") }
    var background by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var signature by remember { mutableStateOf("") }
    var signatureIndex by remember { mutableStateOf(0) }
    var tags by remember { mutableStateOf(listOf<String>()) }
    var tagInput by remember { mutableStateOf("") }
    var generatingTags by remember { mutableStateOf(false) }
    var cropPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(persona?.id) {
        persona?.let { p ->
            name = p.name
            mood = p.spec.mood
            appearance = p.spec.appearance
            background = p.spec.background
            tone = p.spec.tone
            style = p.spec.style
            signature = p.spec.signature
            tags = p.spec.tags
        }
    }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            MediaFiles.importToInternal(context, uri, "persona_avatar_${personaId}_tmp.jpg")?.let { cropPath = it }
        }
    }
    val pickChatBg = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && conversationId != null) scope.launch {
            MediaFiles.importToInternal(context, uri, "chat_bg_${conversationId}.jpg")?.let { path ->
                repo.updateConversationBg(conversationId!!, "file:$path")
            }
        }
    }
    val pickHomeBg = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && persona != null) scope.launch {
            MediaFiles.importToInternal(context, uri, "persona_bg_${personaId}.jpg")?.let { path ->
                repo.updatePersona(persona!!.copy(spec = persona!!.spec.copy(homepageBg = "file:$path")))
            }
        }
    }

    // 头像裁剪
    val cropping = cropPath
    if (cropping != null) {
        AvatarCropperScreen(
            imagePath = cropping,
            onConfirm = { cropped ->
                val current = persona
                scope.launch {
                    if (current != null) {
                        MediaFiles.deleteQuietly(MediaFiles.pathFromRef(current.avatarRef))
                        repo.updatePersona(current.copy(avatarRef = "file:$cropped"))
                    }
                    MediaFiles.deleteQuietly(cropping)
                    cropPath = null
                }
            },
            onCancel = {
                MediaFiles.deleteQuietly(cropping)
                cropPath = null
            },
        )
        return
    }

    val p = persona
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("修改 · ${p?.name ?: ""}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (p == null) {
            Text("加载中…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ===== 头像 =====
            SectionTitle("头像")
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AvatarView(p.avatarRef, 84.dp, fontSize = 40.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonaTemplates.AVATARS.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (p.avatarRef == "emoji:$emoji") MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape,
                            )
                            .clickable { scope.launch { repo.updatePersona(p.copy(avatarRef = "emoji:$emoji")) } },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 22.sp) }
                }
            }
            OutlinedButton(
                onClick = { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("从图库选择头像（可裁剪）") }

            // ===== 聊天背景 =====
            SectionTitle("聊天背景")
            val chatBgPath = MediaFiles.pathFromRef(conversation?.bgRef ?: "")
            val chatBgBitmap = remember(chatBgPath) { chatBgPath?.let { MediaFiles.readBitmap(it, 900) } }
            if (chatBgBitmap != null) {
                Box(
                    Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(chatBgBitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            } else {
                Text("未设置（默认纯色背景）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { pickChatBg.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Text("选择") }
                if (!(conversation?.bgRef ?: "").isNullOrEmpty()) {
                    OutlinedButton(onClick = { scope.launch { conversationId?.let { repo.updateConversationBg(it, "") } } }, modifier = Modifier.weight(1f)) { Text("清除") }
                }
            }

            // ===== 主页背景 =====
            SectionTitle("角色主页背景")
            val homeBgPath = MediaFiles.pathFromRef(p.spec.homepageBg)
            val homeBgBitmap = remember(homeBgPath) { homeBgPath?.let { MediaFiles.readBitmap(it, 900) } }
            if (homeBgBitmap != null) {
                Box(
                    Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(homeBgBitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            } else {
                Text("未设置（使用年龄段的默认渐变背景）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { pickHomeBg.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Text("选择") }
                if (p.spec.homepageBg.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { scope.launch { repo.updatePersona(p.copy(spec = p.spec.copy(homepageBg = ""))) } },
                        modifier = Modifier.weight(1f),
                    ) { Text("清除") }
                }
            }

            // ===== 个性签名 =====
            SectionTitle("个性签名")
            Text(
                signature.ifBlank { "（未生成，保存后自动生成）" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    val variants = PersonaTemplates.signatureVariants(p)
                    signatureIndex = (signatureIndex + 1) % variants.size
                    signature = variants[signatureIndex]
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("重新生成签名") }

            // ===== 个性标签 =====
            SectionTitle("个性标签")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tags.isEmpty()) {
                    Text("暂无标签", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                tags.forEach { tag ->
                    Surface(
                        onClick = { tags = tags - tag },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text("$tag ✕", Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    placeholder = { Text("自定义标签，如：傲娇") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.padding(horizontal = 2.dp))
                Button(
                    onClick = {
                        val t = tagInput.trim()
                        if (t.isNotEmpty() && t !in tags) tags = tags + t
                        tagInput = ""
                    },
                    enabled = tagInput.isNotBlank(),
                ) { Text("添加") }
            }
            OutlinedButton(
                onClick = {
                    if (generatingTags) return@OutlinedButton
                    generatingTags = true
                    scope.launch {
                        runCatching {
                            val config = settings.currentConfig()
                            if (config.apiKey.isBlank()) return@runCatching
                            val client = DeepSeekClient(config.copy(thinking = false, temperature = 0.8))
                            val sb = StringBuilder()
                            client.chat(
                                listOf(
                                    ChatMessage("system", TAGS_PROMPT),
                                    ChatMessage("user", "角色：${p.name}；关系：${p.relationship}；性格：${p.spec.tone}；风格：${p.spec.style}；背景：${p.spec.background}"),
                                ),
                                emptyList(),
                                onDelta = { sb.append(it) },
                            )
                            val generated = sb.toString()
                                .split('、', '，', ',', '\n')
                                .map { it.trim().removePrefix("•").removePrefix("-").trim() }
                                .filter { it.isNotEmpty() && it.length <= 8 }
                                .take(5)
                            if (generated.isNotEmpty()) tags = generated
                        }
                        generatingTags = false
                    }
                },
                enabled = !generatingTags,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (generatingTags) "生成中…" else "AI 根据角色设定生成标签") }

            // ===== 角色设定 =====
            SectionTitle("角色设定")
            RoundTextField(value = name, onValueChange = { name = it }, label = { Text("名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            RoundTextField(value = mood, onValueChange = { mood = it }, label = { Text("心情") }, placeholder = { Text("如：元气满满 / 有点小emo") }, modifier = Modifier.fillMaxWidth())
            RoundTextField(value = appearance, onValueChange = { appearance = it }, label = { Text("外貌") }, placeholder = { Text("如：黑发双马尾，常穿校服") }, modifier = Modifier.fillMaxWidth())
            RoundTextField(value = background, onValueChange = { background = it }, label = { Text("背景经历") }, placeholder = { Text("如：家里养了一只猫，大学刚毕业") }, modifier = Modifier.fillMaxWidth())
            RoundTextField(value = tone, onValueChange = { tone = it }, label = { Text("性格") }, placeholder = { Text("如：温柔、爱操心、偶尔毒舌") }, modifier = Modifier.fillMaxWidth())
            RoundTextField(value = style, onValueChange = { style = it }, label = { Text("说话风格") }, placeholder = { Text("如：短句、爱用表情、叫用户「哥」") }, modifier = Modifier.fillMaxWidth())

            Button(
                onClick = {
                    scope.launch {
                        repo.updatePersona(
                            p.copy(
                                name = name.trim().ifEmpty { p.name },
                                spec = p.spec.copy(
                                    mood = mood.trim(),
                                    appearance = appearance.trim(),
                                    background = background.trim(),
                                    tone = tone.trim(),
                                    style = style.trim(),
                                    signature = signature.trim(),
                                    tags = tags,
                                ),
                            )
                        )
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

private const val TAGS_PROMPT =
    "根据角色设定生成 3-5 个个性标签（如：傲娇、护短、夜猫子、毒舌、粘人）。只输出标签，用顿号分隔，不要任何其他内容。"
