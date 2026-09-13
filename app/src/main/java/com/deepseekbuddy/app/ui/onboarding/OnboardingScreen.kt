package com.deepseekbuddy.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepseekbuddy.app.data.PersonaRepository
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.ui.common.RoundTextField
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val NEEDS = listOf(
    "被需要感" to "想当爸爸/妈妈/哥哥/姐姐，被依赖",
    "长辈关爱" to "想要长辈般的温暖包容",
    "温暖鼓励" to "想要温柔的鼓励与陪伴",
    "毒舌互怼" to "想要互相吐槽的损友",
    "学习上进" to "想要一起学习进步的搭子",
    "倾听树洞" to "想要一个安静的倾听者",
)

private val INTERESTS = listOf("游戏", "动漫", "学习", "运动", "音乐", "电影", "美食", "宠物", "科技", "八卦")

/**
 * 首次启动向导：性别 → 情感需求 → 兴趣 → 生成第一个角色。
 * 用户画像写入 DataStore，供后续个性化阶段使用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(settings: SettingsStore, repo: PersonaRepository, onDone: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var gender by remember { mutableStateOf("") }
    var needs by remember { mutableStateOf(setOf<String>()) }
    var interests by remember { mutableStateOf(setOf<String>()) }
    var customNeedInput by remember { mutableStateOf("") }
    var customInterestInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val presetNeedKeys = NEEDS.map { it.first }
    val customNeeds = needs.filter { it !in presetNeedKeys }
    val customInterests = interests.filter { it !in INTERESTS }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("欢迎来到搭子", style = MaterialTheme.typography.headlineMedium)
        Text(
            "先回答几个小问题，帮你生成第一个专属角色",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (step) {
            0 -> {
                Text("你的性别？", style = MaterialTheme.typography.titleLarge)
                ChoiceRow("male", "男生", gender) { gender = it }
                ChoiceRow("female", "女生", gender) { gender = it }
            }

            1 -> {
                Text("你希望这个角色给你什么样的感觉？（可多选）", style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NEEDS.forEach { (key, desc) ->
                        val selected = key in needs
                        Surface(
                            onClick = { needs = if (selected) needs - key else needs + key },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(key, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // 自定义需求：添加后可点掉删除
                if (customNeeds.isNotEmpty()) {
                    Text(
                        "自定义",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        customNeeds.forEach { key ->
                            Surface(
                                onClick = { needs = needs - key },
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text("$key ✕", Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundTextField(
                        value = customNeedInput,
                        onValueChange = { customNeedInput = it },
                        placeholder = { Text("自定义需求，如：想要一个懂我的树洞") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val t = customNeedInput.trim()
                            if (t.isNotEmpty()) needs = needs + t
                            customNeedInput = ""
                        },
                        enabled = customNeedInput.isNotBlank(),
                    ) { Text("添加") }
                }
            }

            2 -> {
                Text("你的兴趣？（可多选）", style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    INTERESTS.forEach { key ->
                        val selected = key in interests
                        Surface(
                            onClick = { interests = if (selected) interests - key else interests + key },
                            shape = RoundedCornerShape(20.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(key, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
                // 自定义兴趣
                if (customInterests.isNotEmpty()) {
                    Text(
                        "自定义",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        customInterests.forEach { key ->
                            Surface(
                                onClick = { interests = interests - key },
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text("$key ✕", Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundTextField(
                        value = customInterestInput,
                        onValueChange = { customInterestInput = it },
                        placeholder = { Text("自定义兴趣，如：露营、养猫") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val t = customInterestInput.trim()
                            if (t.isNotEmpty()) interests = interests + t
                            customInterestInput = ""
                        },
                        enabled = customInterestInput.isNotBlank(),
                    ) { Text("添加") }
                }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    if (step < 2) {
                        step++
                    } else {
                        val profileJson = buildJsonObject {
                            put("gender", gender)
                            putJsonArray("interests") { interests.forEach { add(JsonPrimitive(it)) } }
                            putJsonArray("needs") { needs.forEach { add(JsonPrimitive(it)) } }
                        }.toString()
                        settings.setUserProfileJson(profileJson)
                        repo.createOnboardingPersona(gender, needs.toList(), interests.toList())
                        onDone()
                    }
                }
            },
            enabled = when (step) {
                0 -> gender.isNotEmpty()
                else -> needs.isNotEmpty() || interests.isNotEmpty()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (step < 2) "下一步" else "生成我的第一个搭子 ✨")
        }
    }
}

@Composable
private fun ChoiceRow(value: String, label: String, selected: String, onSelect: (String) -> Unit) {
    Surface(
        onClick = { onSelect(value) },
        shape = RoundedCornerShape(12.dp),
        color = if (selected == value) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, Modifier.padding(vertical = 18.dp).fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
    }
}
