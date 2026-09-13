package com.deepseekbuddy.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.ui.common.RoundTextField
import kotlinx.coroutines.launch

/** 个人资料子页面：自定义姓名 + 职业 + 简介，AI 角色可读取 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(settings: SettingsStore, onBack: () -> Unit) {
    val userName by settings.userName.collectAsState(initial = "")
    val userJob by settings.userJob.collectAsState(initial = "")
    val userBio by settings.userBio.collectAsState(initial = "")

    var nameInput by rememberSaveable(userName) { mutableStateOf(userName) }
    var jobInput by rememberSaveable(userJob) { mutableStateOf(userJob) }
    var bioInput by rememberSaveable(userBio) { mutableStateOf(userBio) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(nameInput.ifBlank { "个人资料" }) },
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
            RoundTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("姓名 / 称呼") },
                placeholder = { Text("角色们会这样称呼你") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            RoundTextField(
                value = jobInput,
                onValueChange = { jobInput = it },
                label = { Text("职业") },
                placeholder = { Text("如：设计师 / 学生 / 自由职业") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            RoundTextField(
                value = bioInput,
                onValueChange = { bioInput = it },
                label = { Text("简介") },
                placeholder = { Text("写点关于你的信息，角色会记住并理解你，如：养了两只猫，喜欢露营，最近在备考") },
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        settings.setUserName(nameInput)
                        settings.setUserJob(jobInput)
                        settings.setUserBio(bioInput)
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }
            Text(
                "填写后，所有角色都会在对话中读取这些信息，更了解你。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
