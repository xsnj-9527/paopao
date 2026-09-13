package com.deepseekbuddy.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deepseekbuddy.app.data.SettingsStore
import kotlinx.coroutines.launch

/** 外观子页面：主题模式三选 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(settings: SettingsStore, onBack: () -> Unit) {
    val themeMode by settings.themeMode.collectAsState(initial = "system")
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("外观") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("主题模式", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeOption("system", "跟随系统", themeMode, Modifier.weight(1f)) { scope.launch { settings.setThemeMode(it) } }
                ThemeOption("light", "浅色", themeMode, Modifier.weight(1f)) { scope.launch { settings.setThemeMode(it) } }
                ThemeOption("dark", "深色", themeMode, Modifier.weight(1f)) { scope.launch { settings.setThemeMode(it) } }
            }
            Text(
                "聊天背景在聊天页右上角 ⋮ 中设置（每个角色独立）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeOption(
    value: String,
    label: String,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    Surface(
        onClick = { onSelect(value) },
        shape = RoundedCornerShape(12.dp),
        color = if (selected == value) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Text(
            label,
            Modifier.padding(vertical = 12.dp).fillMaxWidth(),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}
