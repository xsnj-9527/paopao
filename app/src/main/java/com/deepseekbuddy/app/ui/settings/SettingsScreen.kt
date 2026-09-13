package com.deepseekbuddy.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.ui.common.RoundTextField
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onDone: () -> Unit,
    showBack: Boolean = false,
) {
    val apiKey by settings.apiKey.collectAsState(initial = "")
    val model by settings.model.collectAsState(initial = "deepseek-v4-flash")
    val temperature by settings.temperature.collectAsState(initial = 1.2f)
    val thinking by settings.thinking.collectAsState(initial = false)
    val showThinking by settings.showThinking.collectAsState(initial = false)
    val privacyMode by settings.privacyMode.collectAsState(initial = false)
    val capsuleEnabled by settings.capsuleEnabled.collectAsState(initial = true)

    var keyInput by rememberSaveable(apiKey) { mutableStateOf(apiKey) }
    var modelInput by rememberSaveable(model) { mutableStateOf(model) }
    var temp by rememberSaveable(temperature) { mutableStateOf(temperature) }
    var thinkingInput by rememberSaveable(thinking) { mutableStateOf(thinking) }
    var showThinkingInput by rememberSaveable(showThinking) { mutableStateOf(showThinking) }
    var privacyInput by rememberSaveable(privacyMode) { mutableStateOf(privacyMode) }
    var capsuleInput by rememberSaveable(capsuleEnabled) { mutableStateOf(capsuleEnabled) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showBack) {
            TextButton(onClick = onDone) {
                Text("← 返回")
            }
        }
        Text("连接设置", style = MaterialTheme.typography.titleLarge)
        RoundTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("DeepSeek API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        RoundTextField(
            value = modelInput,
            onValueChange = { modelInput = it },
            label = { Text("模型") },
            singleLine = true,
            supportingText = { Text("默认 deepseek-v4-flash：约 ¥1/百万输入、¥2/百万输出") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "温度：${"%.1f".format(temp)}（低=冷静严谨，高=活泼随性）",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(value = temp, onValueChange = { temp = it }, valueRange = 0.2f..1.8f, steps = 15)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("思考模式", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "开启后更缜密但响应慢数倍；日常聊天建议关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = thinkingInput, onCheckedChange = { thinkingInput = it })
        }
        SettingSwitchRow(
            title = "显示思考过程",
            desc = "开启思考模式后，展示角色的推理过程（默认隐藏，更像真人）",
            checked = showThinkingInput,
            onCheckedChange = { showThinkingInput = it },
        )
        SettingSwitchRow(
            title = "隐私模式",
            desc = "限制自动分析对话内容，只保留你明确说「记住」的信息",
            checked = privacyInput,
            onCheckedChange = { privacyInput = it },
        )
        SettingSwitchRow(
            title = "时空胶囊",
            desc = "在你常聊天的时段，每天最多推送 2 次来自日记的关心问候",
            checked = capsuleInput,
            onCheckedChange = { capsuleInput = it },
        )
        Button(
            onClick = {
                scope.launch {
                    settings.setApiKey(keyInput)
                    settings.setModel(modelInput)
                    settings.setTemperature(temp)
                    settings.setThinking(thinkingInput)
                    settings.setShowThinking(showThinkingInput)
                    settings.setPrivacyMode(privacyInput)
                    settings.setCapsuleEnabled(capsuleInput)
                    onDone()
                }
            },
            enabled = keyInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存并开始聊天")
        }
        Text(
            "API Key 仅保存在本机（Keystore 加密），对话内容会发送至 DeepSeek API。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
