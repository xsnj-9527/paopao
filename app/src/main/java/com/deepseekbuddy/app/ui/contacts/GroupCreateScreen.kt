package com.deepseekbuddy.app.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekbuddy.app.data.PersonaRepository
import com.deepseekbuddy.app.data.local.PersonaEntity
import com.deepseekbuddy.app.ui.common.AvatarView
import com.deepseekbuddy.app.ui.common.RoundTextField
import kotlinx.coroutines.launch

/** 新建群聊：选 2 位以上角色 + 群名 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCreateScreen(
    repo: PersonaRepository,
    onDone: (convId: Long) -> Unit,
    onCancel: () -> Unit,
) {
    val personas by repo.observePersonas().collectAsState(initial = emptyList())
    // 必须用可观察状态（mutableStateListOf），否则勾选不触发重组
    val selected = remember { mutableStateListOf<Long>() }
    var title by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("新建群聊") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("群聊名称，如：我的家人群") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "选择成员（${selected.size} 人，至少 2 人）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.weight(1f)) {
                items(personas, key = { it.id }) { p ->
                    MemberRow(p, checked = p.id in selected) { checked ->
                        if (checked) {
                            if (p.id !in selected) selected.add(p.id)
                        } else {
                            selected.remove(p.id)
                        }
                    }
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        val convId = repo.createGroupConversation(
                            title.trim().ifBlank { "${selected.size} 人群聊" },
                            selected.toList(),
                        )
                        onDone(convId)
                    }
                },
                enabled = selected.size >= 2,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("创建群聊") }
        }
    }
}

@Composable
private fun MemberRow(persona: PersonaEntity, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarView(persona.avatarRef, 40.dp, fontSize = 20.sp)
        Text(
            persona.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Checkbox(checked = checked, onCheckedChange = onToggle)
    }
}
