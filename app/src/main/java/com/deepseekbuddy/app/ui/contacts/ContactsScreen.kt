package com.deepseekbuddy.app.ui.contacts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekbuddy.app.data.ConversationItem
import com.deepseekbuddy.app.data.PersonaRepository
import com.deepseekbuddy.app.ui.common.AvatarView
import com.deepseekbuddy.app.ui.common.RoundTextField
import com.deepseekbuddy.app.util.PinyinSort

/**
 * 通讯录：好友列表按姓名首字母排序 + 查询；上方查询框与新建角色上下挨着。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    repo: PersonaRepository,
    onOpenChat: (Long) -> Unit,
    onCreatePersona: () -> Unit,
    onCreateGroup: () -> Unit,
    onEditPersona: (Long) -> Unit,
) {
    val all by repo.observeConversationList().collectAsState(initial = null)
    var query by rememberSaveable { mutableStateOf("") }

    // 通讯录 = 好友（单聊），群聊不在此列；null = 加载中（避免空态闪现）
    val q = query.trim()
    val filtered = remember(all, q) {
        all?.filter { !it.isGroup }?.filter { item ->
            q.isBlank() ||
                item.persona?.name?.contains(q) == true ||
                item.persona?.let { PinyinSort.pinyin(it.name).contains(q.lowercase()) } == true
        }
    }
    val groups = remember(filtered) {
        filtered
            ?.sortedWith(compareBy({ PinyinSort.pinyin(it.persona?.name ?: "") }, { it.persona?.name ?: "" }))
            ?.groupBy { PinyinSort.initial(it.persona?.name ?: "") }
            ?.toSortedMap()
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("通讯录") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 查询框（上方）+ 新建角色 / 新建群聊（下方挨着）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("查询好友") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    cornerRadius = 20.dp,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onCreatePersona, modifier = Modifier.weight(1f)) { Text("＋ 新建角色") }
                Button(onClick = onCreateGroup, modifier = Modifier.weight(1f)) { Text("＋ 新建群聊") }
            }

            when {
                // 加载中：空白占位
                filtered == null -> Unit

                filtered.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (q.isBlank()) "还没有好友，点上方「＋ 新建」创建第一个角色" else "没有找到「$q」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        groups!!.forEach { (letter, list) ->
                            item(key = "header_$letter") {
                                Text(
                                    letter,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                            items(list, key = { "c_${it.convId}" }) { item ->
                                ContactRow(
                                    item = item,
                                    onClick = { onOpenChat(item.convId) },
                                    onLongClick = { item.persona?.let { onEditPersona(it.id) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(item: ConversationItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarView(item.persona?.avatarRef ?: "", 44.dp, fontSize = 22.sp)
        Column {
            Text(item.persona?.name ?: "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "${item.persona?.genderLabel() ?: ""} · ${item.persona?.bandLabel() ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun com.deepseekbuddy.app.agent.persona.Persona.genderLabel(): String =
    if (gender == "male") "男生" else if (gender == "female") "女生" else "伙伴"

private fun com.deepseekbuddy.app.agent.persona.Persona.bandLabel(): String = when (ageBand) {
    "child" -> "萌娃"
    "elder" -> "长辈"
    else -> "青年"
}
