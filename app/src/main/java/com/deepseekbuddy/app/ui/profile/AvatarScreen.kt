package com.deepseekbuddy.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekbuddy.app.agent.persona.PersonaTemplates
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.ui.common.AvatarCropperScreen
import com.deepseekbuddy.app.ui.common.AvatarView
import com.deepseekbuddy.app.util.MediaFiles
import kotlinx.coroutines.launch

/** 头像子页面：emoji 预设 + 图库上传（圆形裁剪） */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AvatarScreen(settings: SettingsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val userAvatar by settings.userAvatar.collectAsState(initial = "")
    val scope = rememberCoroutineScope()

    var croppingPath by remember { mutableStateOf<String?>(null) }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            MediaFiles.importToInternal(context, uri, "user_avatar_tmp.jpg")?.let {
                croppingPath = it
            }
        }
    }

    val cropping = croppingPath
    if (cropping != null) {
        AvatarCropperScreen(
            imagePath = cropping,
            onConfirm = { cropped ->
                scope.launch {
                    MediaFiles.deleteQuietly(MediaFiles.pathFromRef(userAvatar))
                    settings.setUserAvatar("file:$cropped")
                    MediaFiles.deleteQuietly(cropping)
                    croppingPath = null
                }
            },
            onCancel = {
                MediaFiles.deleteQuietly(cropping)
                croppingPath = null
            },
        )
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("头像") },
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
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AvatarView(userAvatar, 96.dp, fontSize = 44.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonaTemplates.AVATARS.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                if (userAvatar == "emoji:$emoji") MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                CircleShape,
                            )
                            .clickable { scope.launch { settings.setUserAvatar("emoji:$emoji") } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emoji, fontSize = 24.sp)
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    pickAvatar.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("从图库选择头像（可裁剪）") }
            Spacer(Modifier.height(8.dp))
        }
    }
}
