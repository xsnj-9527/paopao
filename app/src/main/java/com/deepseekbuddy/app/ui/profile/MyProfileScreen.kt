package com.deepseekbuddy.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.ui.common.AvatarView

/**
 * 【我的】页（Tab）：入口菜单——头像 / 外观 / 设置 / 记忆，
 * 具体功能都在各自子页面，风格统一。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyProfileScreen(
    settings: SettingsStore,
    onOpenProfileEdit: () -> Unit,
    onOpenAvatar: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenNotes: () -> Unit,
) {
    val userAvatar by settings.userAvatar.collectAsState(initial = "")
    val userName by settings.userName.collectAsState(initial = "")
    val userBio by settings.userBio.collectAsState(initial = "")

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("我的") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // 用户卡片：点头像/名字进入资料编辑
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenProfileEdit)
                    .padding(vertical = 10.dp),
            ) {
                AvatarView(userAvatar, 64.dp, fontSize = 30.sp)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(userName.ifBlank { "我" }, style = MaterialTheme.typography.titleLarge)
                    Text(
                        userBio.ifBlank { "点击编辑个人资料，让角色更了解你" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            MenuRow(Icons.Filled.Face, "头像", "卡通或图库照片（可裁剪）", onOpenAvatar)
            MenuRow(Icons.Filled.Star, "外观", "浅色 / 深色 / 跟随系统", onOpenAppearance)
            MenuRow(Icons.Filled.Settings, "设置", "连接 · 模型 · 隐私 · 时空胶囊", onOpenSettings)
            MenuRow(Icons.Filled.List, "便签", "聊天中记下的便签", onOpenNotes)
            MenuRow(Icons.Filled.Info, "记忆", "记忆 · 日记 · 行为准则", onOpenMemory)
            MenuRow(Icons.Filled.Favorite, "收藏", "收藏的聊天消息", onOpenFavorites)
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
