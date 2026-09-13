package com.deepseekbuddy.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.deepseekbuddy.app.data.PersonaRepository
import com.deepseekbuddy.app.ui.common.AvatarView
import com.deepseekbuddy.app.ui.common.RoundTextField
import com.deepseekbuddy.app.util.MediaFiles
import kotlinx.coroutines.launch

/**
 * 群聊设置（… 菜单）：
 * 群成员（点击跳转私聊）/ 群聊名称 / 聊天背景 / 底部红字「解散群聊」
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(
    conversationId: Long,
    repo: PersonaRepository,
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onDisbanded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversation by repo.observeConversation(conversationId).collectAsState(initial = null)
    val members by repo.observeGroupMembers(conversationId).collectAsState(initial = emptyList())

    var titleInput by remember { mutableStateOf("") }
    var showDisbandConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(conversation) { titleInput = conversation?.title.orEmpty() }

    val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            MediaFiles.importToInternal(context, uri, "chat_bg_$conversationId.jpg")?.let { path ->
                repo.updateConversationBg(conversationId, "file:$path")
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("群设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ===== 群成员 =====
            SectionTitle("群成员（${members.size}）")
            members.forEach { m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                repo.getConversationByPersona(m.id)?.let { onOpenChat(it.id) }
                            }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarView(m.avatarRef, 40.dp, fontSize = 20.sp)
                    Text(
                        m.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text("私聊 ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            // ===== 群聊名称 =====
            SectionTitle("群聊名称")
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    placeholder = { Text("群聊名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.padding(horizontal = 2.dp))
                Button(
                    onClick = { scope.launch { repo.renameConversation(conversationId, titleInput) } },
                    enabled = titleInput.isNotBlank() && titleInput != conversation?.title,
                ) { Text("保存") }
            }

            // ===== 聊天背景 =====
            SectionTitle("聊天背景")
            val bgPath = MediaFiles.pathFromRef(conversation?.bgRef ?: "")
            val bgBitmap = remember(bgPath) { bgPath?.let { MediaFiles.readBitmap(it, 900) } }
            if (bgBitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bgBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                Text(
                    "未设置（默认纯色背景）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { pickBg.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f),
                ) { Text("选择背景图") }
                if (!(conversation?.bgRef ?: "").isNullOrEmpty()) {
                    OutlinedButton(
                        onClick = { scope.launch { repo.updateConversationBg(conversationId, "") } },
                        modifier = Modifier.weight(1f),
                    ) { Text("清除") }
                }
            }

            // ===== 解散群聊（红字） =====
            Spacer(Modifier.height(16.dp))
            Text(
                "解散群聊",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDisbandConfirm = true }
                    .padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDisbandConfirm) {
        AlertDialog(
            onDismissRequest = { showDisbandConfirm = false },
            title = { Text("解散群聊？") },
            text = { Text("将删除「${conversation?.title ?: ""}」的群聊、全部聊天记录和成员关系，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.deleteConversation(conversationId)
                        showDisbandConfirm = false
                        onDisbanded()
                    }
                }) { Text("解散", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDisbandConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}
