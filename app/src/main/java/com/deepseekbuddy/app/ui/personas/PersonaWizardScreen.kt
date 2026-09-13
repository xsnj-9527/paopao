package com.deepseekbuddy.app.ui.personas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekbuddy.app.agent.persona.Persona
import com.deepseekbuddy.app.agent.persona.PersonaTemplates
import com.deepseekbuddy.app.data.PersonaRepository
import kotlinx.coroutines.launch

private const val STEP_GENDER = 0
private const val STEP_BAND = 1
private const val STEP_RELATION = 2
private const val STEP_DETAIL = 3

/**
 * 角色创建/编辑向导。
 * onDone(conversationId)：新建返回会话 id（可直接进入聊天）；编辑返回 null。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonaWizardScreen(
    editingPersonaId: Long?,
    repo: PersonaRepository,
    onDone: (Long?) -> Unit,
    onCancel: () -> Unit,
) {
    var step by remember { mutableStateOf(STEP_GENDER) }
    var gender by remember { mutableStateOf("female") }
    var ageBand by remember { mutableStateOf("youth") }
    var relationship by remember { mutableStateOf("好朋友") }
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf("emoji:${PersonaTemplates.AVATARS.first()}") }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 编辑模式：预填现有角色
    LaunchedEffect(editingPersonaId) {
        if (editingPersonaId != null) {
            repo.getPersona(editingPersonaId)?.let { p ->
                gender = p.gender
                ageBand = p.ageBand
                relationship = p.relationship
                name = p.name
                avatar = if (p.avatarRef.startsWith("file:")) p.avatarRef else "emoji:${p.avatarRef.removePrefix("emoji:")}"
            }
            loaded = true
        }
    }

    val suggestions = PersonaTemplates.nameSuggestions(gender, ageBand)

    fun commit() {
        val finalName = name.trim().ifEmpty { suggestions.first() }
        val spec = PersonaTemplates.generateSpec(gender, ageBand, relationship)
        scope.launch {
            if (editingPersonaId == null) {
                val persona = Persona(0, finalName, gender, ageBand, relationship, spec, avatar)
                onDone(repo.createPersonaWithConversation(persona))
            } else {
                val persona = Persona(editingPersonaId, finalName, gender, ageBand, relationship, spec, avatar)
                repo.updatePersona(persona)
                onDone(null)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (editingPersonaId == null) "创建新角色" else "编辑角色") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "第 ${step + 1}/4 步",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (step) {
                STEP_GENDER -> {
                    SectionTitle("角色的性别")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChoiceCard("male", "男生", gender == "male", Modifier.weight(1f)) { gender = it }
                        ChoiceCard("female", "女生", gender == "female", Modifier.weight(1f)) { gender = it }
                    }
                }

                STEP_BAND -> {
                    SectionTitle("角色的年龄段")
                    PersonaTemplates.RELATIONSHIPS.keys.forEach { band ->
                        val selected = band == ageBand
                        Surface(
                            onClick = { ageBand = band; relationship = PersonaTemplates.RELATIONSHIPS[band]!!.first() },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    PersonaTemplates.BAND_DESCRIPTIONS[band]!!.substringBefore('\n'),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    PersonaTemplates.BAND_DESCRIPTIONS[band]!!.substringAfter('\n', ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                STEP_RELATION -> {
                    SectionTitle("与你的关系")
                    val options = PersonaTemplates.RELATIONSHIPS[ageBand] ?: emptyList()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        options.forEach { rel ->
                            Surface(
                                onClick = { relationship = rel },
                                shape = RoundedCornerShape(20.dp),
                                color = if (rel == relationship) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(rel, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                        }
                    }
                    if (ageBand == "child") {
                        Text(
                            "萌娃会叫你「$relationship」，你扮演照顾者——这正是亲代投射的体验",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                STEP_DETAIL -> {
                    SectionTitle("名字")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("给他/她取个名字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { s ->
                            Surface(
                                onClick = { name = s },
                                shape = RoundedCornerShape(16.dp),
                                color = if (s == name) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(s, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                    }
                    SectionTitle("头像")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PersonaTemplates.AVATARS.forEach { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        if (avatar == "emoji:$emoji") MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        CircleShape,
                                    )
                                    .clickable { avatar = "emoji:$emoji" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(emoji, fontSize = 22.sp)
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (step > 0) {
                    Button(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("上一步") }
                }
                if (step < STEP_DETAIL) {
                    Button(
                        onClick = { step++ },
                        modifier = Modifier.weight(1f),
                        enabled = when (step) {
                            STEP_GENDER -> gender.isNotEmpty()
                            STEP_BAND -> ageBand.isNotEmpty()
                            else -> relationship.isNotEmpty()
                        },
                    ) { Text("下一步") }
                } else {
                    Button(onClick = { commit() }, modifier = Modifier.weight(1f)) {
                        Text(if (editingPersonaId == null) "创建并开始聊天" else "保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ChoiceCard(
    value: String,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    Surface(
        onClick = { onSelect(value) },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Box(Modifier.padding(vertical = 24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
