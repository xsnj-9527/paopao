package com.deepseekbuddy.app.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekbuddy.app.data.DiaryRepository
import com.deepseekbuddy.app.data.MemoryRepository
import com.deepseekbuddy.app.data.PersonaRepository
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.data.local.MemoryEntity
import com.deepseekbuddy.app.data.local.PersonaEntity
import com.deepseekbuddy.app.ui.common.RoundTextField
import kotlinx.coroutines.launch

/** 记忆管理页：记忆（按角色分组）/ 日历日记 / 行为准则 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    repo: PersonaRepository,
    memories: MemoryRepository,
    diaries: DiaryRepository,
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    val list by memories.observeMemories().collectAsState(initial = null)
    val memList = list
    val personas by repo.observePersonas().collectAsState(initial = emptyList())
    val diaryList by diaries.observeAll().collectAsState(initial = emptyList())
    val behaviorRules by settings.behaviorRules.collectAsState(initial = "")
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 按角色分组（含无归属旧数据）；null = 加载中
    val groups: List<Pair<PersonaEntity?, List<MemoryEntity>>> = if (memList == null) emptyList() else buildList {
        personas.forEach { p ->
            val mine = memList.filter { it.personaId == p.id }
            if (mine.isNotEmpty()) add(p to mine)
        }
        val orphan = memList.filter { m -> m.personaId == null || personas.none { it.id == m.personaId } }
        if (orphan.isNotEmpty()) add(null to orphan)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("记忆") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (memList?.isNotEmpty() == true) {
                        TextButton(onClick = { showClearConfirm = true }) { Text("清空") }
                    }
                },
            )
        },
        floatingActionButton = {
            IconButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.padding(16.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 3.dp,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加记忆", modifier = Modifier.padding(12.dp))
                }
            }
        },
    ) { padding ->
        when {
            // 加载中：空白占位，避免空态闪现
            memList == null -> Unit

            memList.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有记忆", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "在聊天里说「记住我养猫了」，那个角色会把重要的事记在这里\n\n记忆按角色独立——每个角色只记得你亲口告诉 TA 的事，像真朋友一样",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                groups.forEach { (persona, memoryList) ->
                    item(key = "header_${persona?.id ?: 0}") {
                        Text(
                            if (persona != null) "${persona.name} 知道的" else "未归属（旧数据）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(memoryList, key = { it.id }) { memory ->
                        MemoryRow(
                            memory = memory,
                            onDelete = { scope.launch { memories.delete(memory) } },
                            onTogglePin = { scope.launch { memories.setPinned(memory.id, !memory.pinned) } },
                        )
                    }
                }

                // 日历日记（每天一篇，情绪转折 + 关键事件）
                item(key = "diary_header") {
                    Text(
                        "日历日记",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                    )
                }
                if (diaryList.isEmpty()) {
                    item(key = "diary_empty") {
                        Text(
                            "聊得够多后，会自动生成每天的日记",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(diaryList, key = { "d_${it.date}" }) { d ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(d.content, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { scope.launch { diaries.delete(d) } }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除日记",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // 行为准则（退后台自动分析生成）
                item(key = "rules_header") {
                    Text(
                        "行为准则",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                    )
                }
                item(key = "rules") {
                    Text(
                        behaviorRules.ifBlank { "暂无（App 退后台后会自动分析你的聊天，生成行为准则）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                }
            }
        }
    }

    if (showAddDialog) {
        AddMemoryDialog(
            personas = personas,
            onConfirm = { personaId, content ->
                scope.launch { memories.add(personaId, content) }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部记忆？") },
            text = { Text("所有角色的记忆都会被清空，此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { memories.clearAll() }
                    showClearConfirm = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun MemoryRow(memory: MemoryEntity, onDelete: () -> Unit, onTogglePin: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${memory.category} · 重要度 ${"★".repeat(memory.importance)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = if (memory.pinned) "取消固定" else "固定",
                    tint = if (memory.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddMemoryDialog(
    personas: List<PersonaEntity>,
    onConfirm: (personaId: Long, content: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var selectedPersonaId by remember { mutableStateOf(personas.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加记忆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("例如：我养了一只叫煤球的猫") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("告诉哪个角色？", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    personas.forEach { p ->
                        val selected = p.id == selectedPersonaId
                        Surface(
                            onClick = { selectedPersonaId = p.id },
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                "${p.avatarRef} ${p.name}",
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedPersonaId?.let { onConfirm(it, text.trim()) } },
                enabled = text.isNotBlank() && selectedPersonaId != null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
