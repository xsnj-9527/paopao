package com.deepseekbuddy.app.ui.personas

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekbuddy.app.agent.persona.Persona
import com.deepseekbuddy.app.agent.persona.PersonaTemplates
import com.deepseekbuddy.app.data.PersonaRepository
import com.deepseekbuddy.app.ui.common.AvatarView
import com.deepseekbuddy.app.util.MediaFiles
import kotlinx.coroutines.launch

/**
 * 角色主页：上 1/3 角色背景图（默认按年龄段渐变），下 2/3 头像昵称 + 签名 + 标签，
 * 底部【私聊】（群聊来源才显示）+【修改】。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonaProfileScreen(
    personaId: Long,
    repo: PersonaRepository,
    showPrivateChat: Boolean = false,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenChat: (Long) -> Unit = {},
) {
    val persona by repo.observePersona(personaId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var autoSigned by remember { mutableStateOf(false) }

    // 签名未生成时自动生成（本地模板，符合角色设定）
    LaunchedEffect(persona?.id) {
        val p = persona
        if (p != null && p.spec.signature.isBlank() && !autoSigned) {
            autoSigned = true
            scope.launch {
                repo.updatePersona(p.copy(spec = p.spec.copy(signature = PersonaTemplates.generateSignature(p))))
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(persona?.name ?: "角色主页") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val p = persona
        if (p == null) {
            Text("加载中…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 上 1/3：背景图（自定义或按年龄段默认渐变）
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val bgPath = MediaFiles.pathFromRef(p.spec.homepageBg)
                val bgBitmap = remember(bgPath) { bgPath?.let { MediaFiles.readBitmap(it, 1200) } }
                if (bgBitmap != null) {
                    Image(
                        bitmap = bgBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.linearGradient(bandColors(p.ageBand)))
                    )
                }
            }
            // 下 2/3：头像昵称（紧靠背景）+ 签名 + 标签 + 修改
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(2f)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarView(p.avatarRef, 72.dp, fontSize = 34.sp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${bandLabel(p.ageBand)} · ${p.relationship}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (p.spec.signature.isNotBlank()) {
                    Text(
                        "「${p.spec.signature}」",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (p.spec.tags.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.spec.tags.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    tag,
                                    Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // 私聊（群聊来源才显示）+ 修改入口
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showPrivateChat) {
                        Surface(
                            onClick = {
                                scope.launch { repo.getConversationByPersona(personaId)?.let { onOpenChat(it.id) } }
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("私聊", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Surface(
                        onClick = onEdit,
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("修改", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun bandColors(ageBand: String): List<Color> = when (ageBand) {
    "child" -> listOf(Color(0xFFFFB3C1), Color(0xFFFFD6E0))
    "elder" -> listOf(Color(0xFFF4A261), Color(0xFFFFE8D1))
    else -> listOf(Color(0xFF7C9FFF), Color(0xFFC9D6FF))
}

private fun bandLabel(ageBand: String): String = when (ageBand) {
    "child" -> "萌娃"
    "elder" -> "长辈"
    else -> "青年"
}
