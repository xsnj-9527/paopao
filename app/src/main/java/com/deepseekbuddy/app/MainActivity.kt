package com.deepseekbuddy.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.ui.chat.ChatScreen
import com.deepseekbuddy.app.ui.chat.GroupSettingsScreen
import com.deepseekbuddy.app.ui.contacts.ContactsScreen
import com.deepseekbuddy.app.ui.contacts.GroupCreateScreen
import com.deepseekbuddy.app.ui.memory.FavoritesScreen
import com.deepseekbuddy.app.ui.memory.MemoryScreen
import com.deepseekbuddy.app.ui.notes.NotesScreen
import com.deepseekbuddy.app.ui.onboarding.OnboardingScreen
import com.deepseekbuddy.app.ui.personas.PersonaEditScreen
import com.deepseekbuddy.app.ui.personas.PersonaListScreen
import com.deepseekbuddy.app.ui.personas.PersonaProfileScreen
import com.deepseekbuddy.app.ui.personas.PersonaWizardScreen
import com.deepseekbuddy.app.ui.profile.AppearanceScreen
import com.deepseekbuddy.app.ui.profile.AvatarScreen
import com.deepseekbuddy.app.ui.profile.MyProfileScreen
import com.deepseekbuddy.app.ui.profile.ProfileEditScreen
import com.deepseekbuddy.app.ui.settings.SettingsScreen
import com.deepseekbuddy.app.ui.theme.DeepSeekBuddyTheme

sealed interface Screen {
    data class Chat(val conversationId: Long) : Screen
    data class GroupSettings(val conversationId: Long) : Screen
    data class PersonaProfile(val personaId: Long, val fromGroup: Boolean = false) : Screen
    data class PersonaEdit(val personaId: Long) : Screen
    data class Wizard(val editingPersonaId: Long? = null) : Screen
    data object GroupCreate : Screen
    data object Memory : Screen
    data object Favorites : Screen
    data object Notes : Screen
    data object Settings : Screen
    data object Avatar : Screen
    data object Appearance : Screen
    data object ProfileEdit : Screen
}

/** 底部三 Tab：聊天（主界面）/ 通讯录 / 我的 */
private val TABS = listOf(
    Triple(0, "聊天", Icons.Filled.Home),
    Triple(1, "通讯录", Icons.Filled.List),
    Triple(2, "我的", Icons.Filled.Person),
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val app = context.applicationContext as DeepSeekBuddyApp
            val settings = remember { SettingsStore(app) }
            val themeMode by settings.themeMode.collectAsState(initial = "system")

            DeepSeekBuddyTheme(themeMode = themeMode) {
                Box(Modifier.fillMaxSize()) {
                    val repo = remember { app.container.repository }
                    // 初值 null = 数据加载中：避免首帧闪现设置页/引导页
                    val apiKey by settings.apiKey.collectAsState(initial = null)
                    val personas by repo.observePersonas().collectAsState(initial = null)

                    val permLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { /* 拒绝不阻塞使用 */ }

                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    val key = apiKey
                    val ps = personas
                    when {
                        // 数据加载中：空白占位（毫秒级，避免闪屏）
                        key == null || ps == null -> Unit

                        // 未配置 Key → 设置页（首配）
                        key.isBlank() -> SettingsScreen(settings = settings, onDone = {})

                        // 还没有角色 → 首次启动向导
                        ps.isEmpty() -> OnboardingScreen(settings = settings, repo = repo, onDone = {})

                        // 主界面：三 Tab + 页面栈
                        else -> MainShell(app = app, repo = repo, settings = settings)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainShell(
    app: DeepSeekBuddyApp,
    repo: com.deepseekbuddy.app.data.PersonaRepository,
    settings: SettingsStore,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val stack = remember { mutableStateListOf<Screen>() }

    if (stack.isNotEmpty()) {
        // 全屏页（无底部导航）
        when (val s = stack.last()) {
            is Screen.Chat -> ChatScreen(
                conversationId = s.conversationId,
                onBack = { stack.removeLast() },
                onOpenPersona = { personaId, fromGroup -> stack.add(Screen.PersonaProfile(personaId, fromGroup)) },
                onOpenGroupSettings = { stack.add(Screen.GroupSettings(it)) },
            )

            is Screen.PersonaProfile -> PersonaProfileScreen(
                personaId = s.personaId,
                repo = repo,
                showPrivateChat = s.fromGroup,
                onBack = { stack.removeLast() },
                onEdit = { stack.add(Screen.PersonaEdit(s.personaId)) },
                onOpenChat = { stack.add(Screen.Chat(it)) },
            )

            is Screen.PersonaEdit -> PersonaEditScreen(
                personaId = s.personaId,
                repo = repo,
                settings = settings,
                onBack = { stack.removeLast() },
            )

            is Screen.GroupSettings -> GroupSettingsScreen(
                conversationId = s.conversationId,
                repo = repo,
                onBack = { stack.removeLast() },
                onOpenChat = { personaConvId -> stack.add(Screen.Chat(personaConvId)) },
                onDisbanded = {
                    // 解散后退出：群设置 + 聊天页 两级一起弹出
                    if (stack.size >= 2) {
                        stack.removeLast()
                        stack.removeLast()
                    } else {
                        stack.clear()
                    }
                },
            )

            is Screen.Wizard -> PersonaWizardScreen(
                editingPersonaId = s.editingPersonaId,
                repo = repo,
                onDone = { convId ->
                    stack.removeLast()
                    convId?.let { stack.add(Screen.Chat(it)) }
                },
                onCancel = { stack.removeLast() },
            )

            is Screen.GroupCreate -> GroupCreateScreen(
                repo = repo,
                onDone = { convId ->
                    stack.removeLast()
                    stack.add(Screen.Chat(convId))
                },
                onCancel = { stack.removeLast() },
            )

            Screen.Memory -> MemoryScreen(
                repo = repo,
                memories = app.container.memories,
                diaries = app.container.diaries,
                settings = settings,
                onBack = { stack.removeLast() },
            )

            Screen.Favorites -> FavoritesScreen(
                favorites = app.container.favorites,
                onBack = { stack.removeLast() },
            )

            Screen.Notes -> NotesScreen(
                notes = app.container.notes,
                onBack = { stack.removeLast() },
            )

            Screen.Settings -> SettingsScreen(settings = settings, onDone = { stack.removeLast() }, showBack = true)

            Screen.Avatar -> AvatarScreen(settings = settings, onBack = { stack.removeLast() })

            Screen.Appearance -> AppearanceScreen(settings = settings, onBack = { stack.removeLast() })

            Screen.ProfileEdit -> ProfileEditScreen(settings = settings, onBack = { stack.removeLast() })
        }
        BackHandler { stack.removeLast() }
    } else {
        // 三 Tab 主界面
        Scaffold(
            bottomBar = {
                NavigationBar {
                    TABS.forEach { (index, label, icon) ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(icon as ImageVector, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (selectedTab) {
                    0 -> PersonaListScreen(
                        repo = repo,
                        onOpenChat = { stack.add(Screen.Chat(it)) },
                        onEditPersona = { stack.add(Screen.Wizard(it)) },
                        onOpenMemory = { stack.add(Screen.Memory) },
                    )

                    1 -> ContactsScreen(
                        repo = repo,
                        onOpenChat = { stack.add(Screen.Chat(it)) },
                        onCreatePersona = { stack.add(Screen.Wizard(null)) },
                        onCreateGroup = { stack.add(Screen.GroupCreate) },
                        onEditPersona = { stack.add(Screen.Wizard(it)) },
                    )

                    else -> MyProfileScreen(
                        settings = settings,
                        onOpenProfileEdit = { stack.add(Screen.ProfileEdit) },
                        onOpenAvatar = { stack.add(Screen.Avatar) },
                        onOpenAppearance = { stack.add(Screen.Appearance) },
                        onOpenSettings = { stack.add(Screen.Settings) },
                        onOpenMemory = { stack.add(Screen.Memory) },
                        onOpenFavorites = { stack.add(Screen.Favorites) },
                        onOpenNotes = { stack.add(Screen.Notes) },
                    )
                }
            }
        }
    }
}
