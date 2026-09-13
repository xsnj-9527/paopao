package com.deepseekbuddy.app.ui.personas

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepseekbuddy.app.data.ConversationItem
import com.deepseekbuddy.app.ui.common.AvatarView
import com.deepseekbuddy.app.ui.common.GroupAvatar
import com.deepseekbuddy.app.util.TimeFormat
import kotlinx.coroutines.launch

/** 聊天主界面（Tab）：会话列表（微信式） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaListScreen(
    repo: com.deepseekbuddy.app.data.PersonaRepository,
    onOpenChat: (Long) -> Unit,
    onEditPersona: (Long) -> Unit,
    onOpenMemory: () -> Unit,
    vm: PersonaListViewModel = viewModel(),
) {
    val items by vm.items.collectAsState()
    var deleteTarget by remember { mutableStateOf<ConversationItem?>(null) }
    val scope = rememberCoroutineScope()
    val list = items

    // 清空群聊天记录确认（长按群聊触发；不解散群聊）
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("清空聊天记录？") },
            text = { Text("将清空「${target.displayName}」的所有聊天记录，群聊和成员保留。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.clearConversationMessages(target.convId) }
                    deleteTarget = null
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("搭子") },
                actions = {
                    IconButton(onClick = onOpenMemory) {
                        Icon(Icons.Default.Star, contentDescription = "记忆")
                    }
                },
            )
        },
    ) { padding ->
        when {
            // 加载中：空白占位，避免空态闪现
            list == null -> Unit

            list.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有搭子", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "去「通讯录」页点 ＋ 新建，创建一个属于你的角色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(list, key = { it.convId }) { item ->
                        PersonaRow(
                            item = item,
                            onClick = { onOpenChat(item.convId) },
                            onLongClick = {
                                if (item.isGroup) deleteTarget = item
                                else item.persona?.let { onEditPersona(it.id) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PersonaRow(item: ConversationItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.isGroup) {
            // 群头像：成员头像四宫格合成（不足 4 人右下空白）
            GroupAvatar(item.groupAvatars, 48.dp)
        } else {
            AvatarView(item.persona?.avatarRef ?: "", 48.dp, fontSize = 24.sp)
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isGroup) {
                    Text(
                        "群聊",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                item.lastMessage ?: if (item.isGroup) "${item.groupMemberCount} 位成员" else item.persona?.relationship ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            TimeFormat.relative(item.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
