package com.deepseekbuddy.app.ui.chat

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.deepseekbuddy.app.agent.LogcatLogger
import com.deepseekbuddy.app.DeepSeekBuddyApp
import com.deepseekbuddy.agent.AgentConfig
import com.deepseekbuddy.agent.AgentEngine
import com.deepseekbuddy.agent.ChatMessage
import com.deepseekbuddy.app.agent.context.DayDiaryUpdater
import com.deepseekbuddy.app.agent.context.MemoryExtractor
import com.deepseekbuddy.app.agent.context.RollingSummarizer
import com.deepseekbuddy.app.agent.context.TokenBudget
import com.deepseekbuddy.agent.llm.DeepSeekApiException
import com.deepseekbuddy.agent.llm.DeepSeekClient
import com.deepseekbuddy.agent.llm.DeepSeekNetworkException
import com.deepseekbuddy.app.agent.persona.Persona
import com.deepseekbuddy.app.agent.persona.PersonaTemplates
import com.deepseekbuddy.app.agent.persona.buildSystemPrompt
import com.deepseekbuddy.agent.tools.NoteTool
import com.deepseekbuddy.agent.tools.RememberFactTool
import com.deepseekbuddy.agent.tools.ReminderTool
import com.deepseekbuddy.agent.tools.TimeTool
import com.deepseekbuddy.agent.tools.ToolRegistry
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.reminder.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val streaming: Boolean = false,
    val loading: Boolean = true,
    val pendingConfirm: PendingConfirm? = null,
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val pendingQuote: PendingQuote? = null,
    val menuTarget: MessageMenuTarget? = null,
)

/** 待用户确认的操作（CONFIRM 工具） */
data class PendingConfirm(val name: String, val argsSummary: String)

/** 待发送的引用 */
data class PendingQuote(val dbId: Long, val content: String)

/** 长按消息弹出的操作菜单目标 */
data class MessageMenuTarget(
    val dbId: Long,
    val isUser: Boolean,
    val content: String,
    val createdAt: Long,
    val recalled: Boolean,
)

sealed interface UiMessage {
    val id: Long

    data class User(
        override val id: Long,
        val text: String,
        val dbId: Long? = null,
        val recalled: Boolean = false,
        val quotedContent: String? = null,
    ) : UiMessage
    data class Assistant(
        override val id: Long,
        val text: String,
        val streaming: Boolean,
        val reasoning: String = "",
        val dbId: Long? = null,
        val recalled: Boolean = false,
        val quotedContent: String? = null,
        /** 消息旁头像：群聊按发言者匹配；null 则用会话默认 */
        val senderAvatar: String? = null,
        /** 群聊发言者名字（显示在气泡上方） */
        val senderName: String? = null,
        /** 群聊发言者角色 id（点头像进主页用） */
        val senderPersonaId: Long? = null,
    ) : UiMessage
    data class ToolCallCard(
        override val id: Long,
        val name: String,
        val argsSummary: String,
        val result: String? = null,
        val success: Boolean? = null,
    ) : UiMessage

    data class Error(override val id: Long, val text: String) : UiMessage
}

class ChatViewModelFactory(
    private val application: Application,
    private val conversationId: Long,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ChatViewModel::class.java) ->
            ChatViewModel(application, conversationId) as T
        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

/**
 * 会话级聊天 ViewModel：一个角色一个会话（微信式）。
 * 历史从 Room 加载；新会话自动插入「三句话速写」开场白。
 */
class ChatViewModel(
    application: Application,
    private val conversationId: Long,
) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val scheduler = ReminderScheduler(application)
    private val app = application as DeepSeekBuddyApp
    private val memories = app.container.memories
    private val registry = ToolRegistry(
        listOf(
            ReminderTool(scheduler),
            TimeTool(),
            RememberFactTool(memories),
            NoteTool(app.container.notes),
        )
    )
    private val engine = AgentEngine(
        registry,
        { config -> DeepSeekClient(config, LogcatLogger) },
        LogcatLogger,
    )
    private val summarizer = RollingSummarizer { config -> DeepSeekClient(config, LogcatLogger) }
    private val extractor = MemoryExtractor { config -> DeepSeekClient(config, LogcatLogger) }
    private val diaryUpdater = DayDiaryUpdater(app.container.diaries) { config -> DeepSeekClient(config, LogcatLogger) }
    private val repo = app.container.repository
    private val diaries = app.container.diaries

    @Volatile
    private var pendingConfirmDeferred: CompletableDeferred<Boolean>? = null

    private var persona: Persona? = null
    private var groupTitle: String? = null
    private var participants: List<Persona> = emptyList()

    /** 历史条目（含持久化元数据：id/时间/撤回/引用，供消息操作使用） */
    private data class HistoryEntry(
        val role: String,
        val content: String,
        val dbId: Long? = null,
        val createdAt: Long = 0,
        val recalled: Boolean = false,
        val quotedId: Long? = null,
    ) {
        fun toChatMessage() = ChatMessage(role, content)
    }

    private val history = mutableListOf<HistoryEntry>()
    private var nextId = 1L
    private var runningJob: Job? = null

    private val _ui = MutableStateFlow(ChatUiState(loading = true))
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _personaId = MutableStateFlow<Long?>(null)
    val personaId: StateFlow<Long?> = _personaId.asStateFlow()

    private val _isGroup = MutableStateFlow(false)
    val isGroup: StateFlow<Boolean> = _isGroup.asStateFlow()

    /** 群成员（@ 功能与输入提示用） */
    data class GroupMember(val name: String, val avatarRef: String)

    private val _groupMembers = MutableStateFlow<List<GroupMember>>(emptyList())
    val groupMembers: StateFlow<List<GroupMember>> = _groupMembers.asStateFlow()

    /** 单聊时角色头像（消息旁展示） */
    private val _assistantAvatar = MutableStateFlow("")
    val assistantAvatar: StateFlow<String> = _assistantAvatar.asStateFlow()

    /** 群聊：按发言者名字匹配头像 */
    private fun avatarFor(sender: String?): String? =
        sender?.let { n -> participants.firstOrNull { it.name == n }?.avatarRef }

    /** 群聊：按发言者名字匹配角色 id */
    private fun idFor(sender: String?): Long? =
        sender?.let { n -> participants.firstOrNull { it.name == n }?.id }

    init {
        viewModelScope.launch {
            val conv = repo.getConversation(conversationId) ?: return@launch

            if (conv.isGroup) {
                // 群聊：加载成员，不注入单角色记忆/开场白
                groupTitle = conv.title
                _title.value = conv.title
                _isGroup.value = true
                participants = repo.participantsFor(conversationId)
                _groupMembers.value = participants.map { GroupMember(it.name, it.avatarRef) }
                loadHistoryIntoMemory()
                _ui.update { it.copy(messages = buildUiMessages(), loading = false) }
                return@launch
            }

            val p = repo.getPersona(conv.personaId) ?: return@launch
            persona = p
            _personaId.value = p.id
            _title.value = p.name
            _assistantAvatar.value = p.avatarRef

            loadHistoryIntoMemory()
            if (history.isEmpty()) {
                // 三句话速写：新角色首会话本地开场白
                val lines = PersonaTemplates.openingLines(p)
                repo.insertOpeningLines(conversationId, lines)
                loadHistoryIntoMemory()
            }
            _ui.update { it.copy(messages = buildUiMessages(), loading = false) }

            // 角色在 … 菜单被编辑后（改名/心情/外貌等），聊天页实时刷新
            viewModelScope.launch {
                repo.observePersona(p.id).collect { updated ->
                    updated?.let {
                        persona = it
                        _title.value = it.name   // 改名后顶部标题同步
                    }
                }
            }
        }
    }

    private suspend fun loadHistoryIntoMemory() {
        history.clear()
        history += repo.loadMessages(conversationId).map { m ->
            HistoryEntry(
                role = m.role,
                content = m.content,
                dbId = m.id,
                createdAt = m.createdAt,
                recalled = m.recalled,
                quotedId = m.quotedId,
            )
        }
    }

    private fun buildUiMessages(): List<UiMessage> {
        val byDbId = history.mapNotNull { it.dbId?.let { id -> id to it } }.toMap()
        val isGroupChat = groupTitle != null
        return history.flatMap { m ->
            val quoted = m.quotedId?.let { byDbId[it]?.content }
            if (m.role == "user") {
                listOf(UiMessage.User(nextId++, m.content, dbId = m.dbId, recalled = m.recalled, quotedContent = quoted))
            } else {
                // 群聊按发言者拆分；单聊按空行拆多条短消息（微信式连发）
                val parts = splitReplyIntoBubbles(m.content)
                parts.map { (sender, text) ->
                    UiMessage.Assistant(
                        nextId++, text, streaming = false,
                        dbId = m.dbId, recalled = m.recalled, quotedContent = quoted,
                        senderAvatar = if (isGroupChat) (avatarFor(sender) ?: "👥") else null,
                        senderName = if (isGroupChat) sender else null,
                        senderPersonaId = if (isGroupChat) idFor(sender) else null,
                    )
                }
            }
        }
    }

    /** 回复拆气泡：群聊先按发言者，再按空行分段（单聊直接按空行分段） */
    private fun splitReplyIntoBubbles(content: String): List<Pair<String?, String>> {
        val bySpeaker = if (groupTitle != null) splitGroupReply(content) else listOf(null to content)
        val result = mutableListOf<Pair<String?, String>>()
        for ((sender, text) in bySpeaker) {
            val paragraphs = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            if (paragraphs.size <= 1) {
                result.add(sender to text)
            } else {
                paragraphs.take(MAX_BUBBLES_PER_REPLY).forEach { result.add(sender to it) }
            }
        }
        return result.ifEmpty { listOf(null to content) }
    }

    /** 群聊回复拆分：按【名字】前缀切分为 (发言者, 内容) 列表；无标注时整体返回 */
    private fun splitGroupReply(content: String): List<Pair<String?, String>> {
        val result = mutableListOf<Pair<String?, String>>()
        var currentSender: String? = null
        val currentText = StringBuilder()
        for (line in content.split("\n")) {
            val m = SENDER_RE.find(line)
            if (m != null) {
                if (currentSender != null && currentText.isNotBlank()) {
                    result.add(currentSender to currentText.toString().trim())
                }
                currentSender = m.groupValues[1]
                currentText.clear()
                val rest = line.substring(m.range.last + 1).trim()
                if (rest.isNotEmpty()) currentText.append(rest)
            } else {
                if (currentText.isNotEmpty()) currentText.append('\n')
                currentText.append(line)
            }
        }
        if (currentSender != null && currentText.isNotBlank()) {
            result.add(currentSender to currentText.toString().trim())
        }
        return result.ifEmpty { listOf(null to content) }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _ui.value.streaming || _ui.value.loading) return
        val isGroup = groupTitle != null
        val p = persona
        if (!isGroup && p == null) return
        val quote = _ui.value.pendingQuote
        runningJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    streaming = true,
                    messages = it.messages + UiMessage.User(nextId++, trimmed, quotedContent = quote?.content),
                )
            }
            _ui.update { it.copy(messages = it.messages + UiMessage.Assistant(nextId++, "", streaming = true)) }
            try {
                val config = settings.currentConfig()
                val conv = repo.getConversation(conversationId)
                var summary = conv?.summary.orEmpty()

                // 上下文预算：历史超阈值 → 滚动摘要压缩 → 裁剪保留最近部分
                var ctxHistory = history.map { it.toChatMessage() }
                if (TokenBudget.estimate(ctxHistory) > TokenBudget.SUMMARY_TRIGGER) {
                    val newSummary = summarizer.summarize(config, ctxHistory)
                    if (newSummary.isNotBlank()) {
                        summary = newSummary
                        repo.setConversationSummary(conversationId, newSummary)
                    }
                    ctxHistory = TokenBudget.trimHistory(ctxHistory)
                }

                val systemPrompt = if (isGroup) {
                    buildGroupPrompt(summary)
                } else {
                    buildPromptWithMemories(p!!, summary)
                }
                // 引用：拼进发给模型的内容，让角色知道在回应什么
                val userText = if (quote != null) "[引用] ${quote.content}\n$trimmed" else trimmed
                val finalText = engine.run(
                    config,
                    ctxHistory,
                    userText,
                    systemPrompt = systemPrompt,
                    personaId = if (isGroup) -1 else p!!.id,
                    onDelta = { d -> _ui.update { s -> s.copy(messages = s.messages.withLastAssistantText { it + d }) } },
                    onReasoning = { r -> _ui.update { s -> s.copy(messages = s.messages.withLastAssistantReasoning { it + r }) } },
                    onConfirmRequest = { name, args -> requestConfirm(name, args) },
                    onToolCall = { name, args -> _ui.update { s -> s.copy(messages = s.messages + UiMessage.ToolCallCard(nextId++, name, formatArgs(name, args))) } },
                    onToolResult = { r -> _ui.update { s -> s.copy(messages = s.messages.withLastToolCardResult(r.success, r.message)) } },
                )
                val now = System.currentTimeMillis()
                val userDbId = repo.appendMessage(conversationId, "user", trimmed, quotedId = quote?.dbId)
                val senderName = senderOf(finalText)
                val senderAvatar = if (isGroup) (avatarFor(senderName) ?: "👥") else null
                val assistantDbId = repo.appendMessage(conversationId, "assistant", finalText, senderName = senderName)
                history += HistoryEntry("user", trimmed, dbId = userDbId, createdAt = now, quotedId = quote?.dbId)
                history += HistoryEntry("assistant", finalText, dbId = assistantDbId, createdAt = now)

                // 把 dbId/头像 补写回 UI 消息（否则刚发的消息长按无效）
                _ui.update { s ->
                    val msgs = s.messages.toMutableList()
                    val userIdx = msgs.indexOfLast { it is UiMessage.User && it.dbId == null }
                    if (userIdx >= 0) {
                        msgs[userIdx] = (msgs[userIdx] as UiMessage.User).copy(dbId = userDbId, quotedContent = quote?.content)
                    }
                    val asstIdx = msgs.indexOfLast { it is UiMessage.Assistant && it.dbId == null }
                    if (asstIdx >= 0) {
                        val live = msgs[asstIdx] as UiMessage.Assistant
                        // 按发言者/空行拆分为多条独立气泡
                        val parts = splitReplyIntoBubbles(finalText)
                        val newItems = parts.map { (sender, text) ->
                            live.copy(
                                text = text,
                                streaming = false,
                                dbId = assistantDbId,
                                senderAvatar = if (isGroup) (avatarFor(sender) ?: "👥") else null,
                                senderName = if (isGroup) sender else null,
                                senderPersonaId = if (isGroup) idFor(sender) else null,
                            )
                        }
                        msgs.removeAt(asstIdx)
                        msgs.addAll(asstIdx, newItems)
                    }
                    s.copy(messages = msgs)
                }

                // 阶段 4 后置任务：记忆自动抽取（每 10 轮）+ 日历日记（每 20 轮）
                if (isGroup) launchGroupPostTurnTasks(config) else launchPostTurnTasks(p!!, config)
            } catch (e: CancellationException) {
                Log.d(TAG, "send cancelled by user")
                throw e
            } catch (e: DeepSeekNetworkException) {
                Log.e(TAG, "network error", e)
                _ui.update { it.copy(messages = it.messages + UiMessage.Error(nextId++, "网络错误：${e.message}")) }
            } catch (e: DeepSeekApiException) {
                Log.e(TAG, "api error", e)
                _ui.update { it.copy(messages = it.messages + UiMessage.Error(nextId++, e.message ?: "API 错误")) }
            } catch (e: Exception) {
                Log.e(TAG, "unexpected error", e)
                _ui.update { it.copy(messages = it.messages + UiMessage.Error(nextId++, "出错了：${e.message}")) }
            } finally {
                _ui.update {
                    it.copy(
                        streaming = false,
                        messages = it.messages.finishStreaming(),
                        pendingConfirm = null,
                        pendingQuote = null,
                    )
                }
            }
        }
    }

    fun stop() {
        runningJob?.cancel()
    }

    // ==================== 消息操作（长按菜单 / 多选） ====================

    fun openMessageMenu(dbId: Long) {
        val e = history.firstOrNull { it.dbId == dbId } ?: return
        _ui.update {
            it.copy(menuTarget = MessageMenuTarget(e.dbId!!, e.role == "user", e.content, e.createdAt, e.recalled))
        }
    }

    fun dismissMenu() = _ui.update { it.copy(menuTarget = null) }

    fun copyMessage() {
        val t = _ui.value.menuTarget ?: return
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("消息", t.content))
        dismissMenu()
    }

    fun quoteMessage() {
        val t = _ui.value.menuTarget ?: return
        _ui.update { it.copy(pendingQuote = PendingQuote(t.dbId, t.content), menuTarget = null) }
    }

    fun cancelQuote() = _ui.update { it.copy(pendingQuote = null) }

    fun recallMessage() {
        val t = _ui.value.menuTarget ?: return
        val idx = history.indexOfFirst { it.dbId == t.dbId }
        val canRecall = t.isUser && !t.recalled && System.currentTimeMillis() - t.createdAt <= RECALL_WINDOW_MS
        dismissMenu()
        if (idx < 0 || !canRecall) return
        viewModelScope.launch {
            repo.recallMessage(t.dbId)
            history[idx] = history[idx].copy(recalled = true)
            _ui.update { it.copy(messages = buildUiMessages()) }
        }
    }

    fun deleteMessage() {
        val t = _ui.value.menuTarget ?: return
        dismissMenu()
        viewModelScope.launch {
            repo.deleteMessageById(t.dbId)
            history.removeAll { it.dbId == t.dbId }
            _ui.update { it.copy(messages = buildUiMessages()) }
        }
    }

    fun enterMultiSelect() {
        val t = _ui.value.menuTarget ?: return
        _ui.update { it.copy(selectionMode = true, selectedIds = setOf(t.dbId), menuTarget = null) }
    }

    fun toggleSelect(dbId: Long) {
        _ui.update { s ->
            val ids = if (dbId in s.selectedIds) s.selectedIds - dbId else s.selectedIds + dbId
            s.copy(selectedIds = ids)
        }
    }

    fun cancelSelection() = _ui.update { it.copy(selectionMode = false, selectedIds = emptySet()) }

    /** 转发：把选中的消息作为你的发言插入目标会话 */
    fun forwardSelected(targetConvId: Long) {
        val contents = selectedContents()
        if (contents.isEmpty()) return
        viewModelScope.launch {
            contents.forEach { repo.appendMessage(targetConvId, "user", it) }
            cancelSelection()
        }
    }

    /** 收藏选中的消息 */
    fun favoriteSelected() {
        val contents = selectedContents()
        if (contents.isEmpty()) return
        viewModelScope.launch {
            contents.forEach { app.container.favorites.add(it) }
            cancelSelection()
        }
    }

    /** 导出选中的消息为文件并调起分享 */
    fun exportSelected() {
        val selected = history.filter { it.dbId in _ui.value.selectedIds }
        if (selected.isEmpty()) return
        val sb = StringBuilder("「${_title.value}」聊天记录导出\n共 ${selected.size} 条\n")
        sb.append("-".repeat(30)).append('\n')
        selected.forEach { e ->
            val who = if (e.role == "user") "我" else "角色"
            sb.append("[${who}] ${e.content}\n")
        }
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "聊天记录_${System.currentTimeMillis()}.txt")
        file.writeText(sb.toString())
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "导出聊天记录")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(chooser) }
        cancelSelection()
    }

    private fun selectedContents(): List<String> =
        history.filter { it.dbId in _ui.value.selectedIds }.map { it.content }

    /** CONFIRM 工具确认桥：挂起引擎，等用户在对话框选择 */
    private suspend fun requestConfirm(name: String, argsJson: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmDeferred = deferred
        _ui.update { it.copy(pendingConfirm = PendingConfirm(name, formatArgs(name, argsJson))) }
        return try {
            deferred.await()
        } finally {
            _ui.update { it.copy(pendingConfirm = null) }
        }
    }

    fun respondConfirm(allowed: Boolean) {
        pendingConfirmDeferred?.let { d ->
            pendingConfirmDeferred = null
            d.complete(allowed)
        }
    }

    /** 人设提示词 + 用户档案 + 滚动摘要 + 3 天日记 + 行为准则 + 该角色专属记忆注入 */
    private suspend fun buildPromptWithMemories(p: Persona, summary: String): String {
        val sb = StringBuilder(p.buildSystemPrompt())
        sb.append(userProfileSection())
        if (summary.isNotBlank()) {
            sb.append("\n【当前会话背景】$summary\n")
        }
        val recentDiaries = diaries.recentDays(3)
        if (recentDiaries.isNotEmpty()) {
            sb.append("\n【最近几天】\n")
            recentDiaries.forEach { sb.append("- ${it.date}：${it.content}\n") }
        }
        val rules = settings.behaviorRulesValue()
        if (rules.isNotBlank()) {
            sb.append("\n【行为准则】\n$rules\n")
        }
        val injected = memories.forInjection(p.id)
        if (injected.isNotEmpty()) {
            memories.touch(injected.map { it.id })
            sb.append("\n【关于用户】\n")
            injected.forEach { sb.append("- ${it.content}\n") }
        }
        return sb.toString()
    }

    /** 群聊提示词：每位成员保持人设 + 各自记忆 + 群聊规则（发言标注） */
    private suspend fun buildGroupPrompt(summary: String): String = buildString {
        append("你正在群聊「${groupTitle ?: "群聊"}」中，群成员：${participants.joinToString("、") { it.name }}。你扮演每一位成员，各自保持自己的人设。\n")
        append(userProfileSection())
        participants.forEach { p ->
            append("\n【成员 ${p.name}】\n${p.buildSystemPrompt()}\n")
            // 成员带着自己的记忆参与群聊（私聊中知道的事，群里也知道）
            val memberMemories = memories.forInjection(p.id, limit = 5)
            if (memberMemories.isNotEmpty()) {
                memories.touch(memberMemories.map { it.id })
                append("【${p.name} 记得的关于用户的事】\n")
                memberMemories.forEach { append("- ${it.content}\n") }
            }
        }
        if (summary.isNotBlank()) append("\n【当前会话背景】$summary\n")
        append("""
            |
            |【群聊规则】
            |- 每条回复先标注发言者：【名字】内容
            |- 用户说话时由最相关的成员先回应，其他成员可以插话、附和或吐槽
            |- 成员之间可以互相聊天、开玩笑，像真实群聊
            |- 各自保持性格，不要都变成同一种语气
            |- 每个成员单条发言不超过 3 行，简短自然，像真实群聊
            |- 用户使用 @名字 时，被 @ 的成员优先回应；@全体成员 时所有成员都回应
            |- 需要工具（提醒/便签等）时由合适的成员调用
        """.trimMargin())
    }

    /** 用户档案段（我的-资料页填写，所有角色可读取） */
    private suspend fun userProfileSection(): String {
        val p = settings.userProfile()
        if (p.name.isBlank() && p.job.isBlank() && p.bio.isBlank()) return ""
        return buildString {
            append("\n【用户档案】\n")
            if (p.name.isNotBlank()) append("- 姓名：${p.name}\n")
            if (p.job.isNotBlank()) append("- 职业：${p.job}\n")
            if (p.bio.isNotBlank()) append("- 简介：${p.bio}\n")
        }
    }

    /** 群聊发言者解析：【名字】前缀 */
    private fun senderOf(text: String): String? =
        SENDER_RE.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** 阶段 4 后置任务（失败静默，不影响主流程）；隐私模式跳过 */
    private fun launchPostTurnTasks(p: Persona, config: AgentConfig) {
        viewModelScope.launch {
            runCatching {
                if (settings.privacyModeValue()) return@runCatching
                val count = history.size
                if (count % EXTRACT_EVERY == 0) {
                    val facts = extractor.extract(config, history.takeLast(EXTRACT_EVERY).map { it.toChatMessage() })
                    if (facts.isNotEmpty()) {
                        val existing = memories.contentsByPersona(p.id)
                        facts.forEach { f ->
                            if (f.content !in existing) {
                                memories.add(p.id, f.content, f.category, f.importance)
                                Log.d(TAG, "auto-extracted: ${f.content}")
                            }
                        }
                    }
                }
                if (count % DIARY_EVERY == 0) {
                    diaryUpdater.update(config, history.takeLast(DIARY_EVERY).map { it.toChatMessage() })
                    Log.d(TAG, "day diary updated")
                }
            }
        }
    }

    /** 群聊后置任务：抽取的事实写入所有群成员（群聊内容成员共享记忆，私聊时也记得） */
    private fun launchGroupPostTurnTasks(config: AgentConfig) {
        viewModelScope.launch {
            runCatching {
                if (settings.privacyModeValue()) return@runCatching
                val count = history.size
                if (count % EXTRACT_EVERY == 0) {
                    val facts = extractor.extract(config, history.takeLast(EXTRACT_EVERY).map { it.toChatMessage() })
                    if (facts.isNotEmpty()) {
                        participants.forEach { member ->
                            val existing = memories.contentsByPersona(member.id)
                            facts.forEach { f ->
                                if (f.content !in existing) {
                                    memories.add(member.id, f.content, f.category, f.importance)
                                }
                            }
                        }
                        Log.d(TAG, "group-extracted ${facts.size} facts -> ${participants.size} members")
                    }
                }
                if (count % DIARY_EVERY == 0) {
                    diaryUpdater.update(config, history.takeLast(DIARY_EVERY).map { it.toChatMessage() })
                }
            }
        }
    }

    companion object {
        private const val TAG = "DeepSeekBuddy"
        private const val EXTRACT_EVERY = 10   // 每 10 轮自动抽取记忆
        private const val DIARY_EVERY = 20     // 每 20 轮更新当日日记
        private const val RECALL_WINDOW_MS = 3 * 60 * 1000L   // 3 分钟内可撤回
        private const val MAX_BUBBLES_PER_REPLY = 6           // 单条回复最多拆成几条
        private val SENDER_RE = Regex("^【([^】]+)】")
    }

    private fun formatArgs(name: String, argsJson: String): String {
        val obj = runCatching { Json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
            ?: return name
        return buildString {
            append(name)
            obj.forEach { (k, v) ->
                val value = runCatching { v.jsonPrimitive.content }.getOrDefault(v.toString())
                append("\n  $k: $value")
            }
        }
    }

    private fun List<UiMessage>.withLastAssistantText(transform: (String) -> String): List<UiMessage> {
        val index = indexOfLast { it is UiMessage.Assistant }
        if (index < 0) return this
        return toMutableList().also {
            val m = it[index] as UiMessage.Assistant
            it[index] = m.copy(text = transform(m.text))
        }
    }

    private fun List<UiMessage>.withLastAssistantReasoning(transform: (String) -> String): List<UiMessage> {
        val index = indexOfLast { it is UiMessage.Assistant }
        if (index < 0) return this
        return toMutableList().also {
            val m = it[index] as UiMessage.Assistant
            it[index] = m.copy(reasoning = transform(m.reasoning))
        }
    }

    private fun List<UiMessage>.withLastToolCardResult(success: Boolean, message: String): List<UiMessage> {
        val index = indexOfLast { it is UiMessage.ToolCallCard }
        if (index < 0) return this
        return toMutableList().also {
            val m = it[index] as UiMessage.ToolCallCard
            it[index] = m.copy(result = message, success = success)
        }
    }

    private fun List<UiMessage>.finishStreaming(): List<UiMessage> =
        map { m -> if (m is UiMessage.Assistant) m.copy(streaming = false) else m }
}
