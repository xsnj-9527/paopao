package com.deepseekbuddy.app.data

import android.content.Context
import com.deepseekbuddy.app.agent.ChatMessage
import com.deepseekbuddy.app.agent.persona.Persona
import com.deepseekbuddy.app.agent.persona.PersonaTemplates
import com.deepseekbuddy.app.agent.persona.toEntity
import com.deepseekbuddy.app.agent.persona.toPersona
import com.deepseekbuddy.app.data.local.AppDao
import com.deepseekbuddy.app.data.local.AppDatabase
import com.deepseekbuddy.app.data.local.ConversationEntity
import com.deepseekbuddy.app.data.local.MessageEntity
import com.deepseekbuddy.app.data.local.ParticipantEntity
import com.deepseekbuddy.app.data.local.PersonaEntity
import com.deepseekbuddy.app.util.PinyinSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class ConversationItem(
    val convId: Long,
    val persona: Persona? = null,        // 单聊角色；群聊为 null
    val groupTitle: String? = null,      // 群聊标题
    val groupAvatars: List<String> = emptyList(),  // 群头像合成用（含用户，按首字母取前 4）
    val groupMemberCount: Int = 0,       // 群成员数（不含用户）
    val lastMessage: String?,
    val updatedAt: Long,
) {
    val isGroup: Boolean get() = persona == null
    val displayName: String get() = persona?.name ?: groupTitle.orEmpty()
}

/** 应用级容器：手动 DI（Hilt 留到后续阶段） */
class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.getInstance(context)
    val dao = database.dao()
    private val settingsStore = SettingsStore(context)
    val repository = PersonaRepository(dao, settingsStore)
    val memories = MemoryRepository(dao)
    val notes = NotesRepository(dao)
    val diaries = DiaryRepository(dao)
    val favorites = FavoritesRepository(dao)
}

class PersonaRepository(
    private val dao: AppDao,
    private val settings: SettingsStore,
) {

    fun observePersonas(): Flow<List<PersonaEntity>> = dao.observePersonas()

    /** 会话列表（微信式）：单聊 + 群聊混合，按最近聊天排序 */
    fun observeConversationList(): Flow<List<ConversationItem>> =
        combine(
            dao.observeConversations(),
            dao.observePersonas(),
            dao.observeParticipantsWithPersona(),
        ) { convs, personas, participants ->
            val byId = personas.associateBy { it.id }
            val membersByConv = participants.groupBy { it.conversationId }
            convs.map { c ->
                if (c.isGroup) {
                    val members = membersByConv[c.id].orEmpty().mapNotNull { byId[it.persona.id] }
                    // 群头像：成员 + 用户（群主）按姓名首字母排序取前 4 位合成
                    val userName = settings.userProfile().name.ifBlank { "我" }
                    val userAvatar = settings.userAvatarValue().ifBlank { "🙂" }
                    val avatarPool = members
                        .map { PinyinSort.pinyin(it.name) to it.avatarRef } +
                        (PinyinSort.pinyin(userName) to userAvatar)
                    val topAvatars = avatarPool.sortedBy { it.first }.take(4).map { it.second }
                    ConversationItem(
                        convId = c.id,
                        groupTitle = c.title,
                        groupAvatars = topAvatars,
                        groupMemberCount = members.size,
                        lastMessage = c.lastMessage,
                        updatedAt = c.updatedAt,
                    )
                } else {
                    val p = byId[c.personaId]?.toPersona()
                    ConversationItem(
                        convId = c.id,
                        persona = p,
                        lastMessage = c.lastMessage,
                        updatedAt = c.updatedAt,
                    )
                }
            }
        }

    suspend fun getPersona(id: Long): Persona? = dao.getPersona(id)?.toPersona()

    /** 角色实时流（编辑后聊天页自动刷新） */
    fun observePersona(id: Long): Flow<Persona?> = dao.observePersona(id).map { it?.toPersona() }

    suspend fun getConversation(id: Long): ConversationEntity? = dao.getConversation(id)

    suspend fun getConversationByPersona(personaId: Long): ConversationEntity? = dao.getConversationByPersona(personaId)

    fun observeConversation(id: Long): Flow<ConversationEntity?> = dao.observeConversation(id)

    suspend fun updateConversationBg(convId: Long, bgRef: String) = dao.updateConversationBg(convId, bgRef)

    suspend fun setConversationSummary(convId: Long, summary: String) =
        dao.updateConversationSummary(convId, summary)

    /** 创建角色 + 专属会话，返回会话 id */
    suspend fun createPersonaWithConversation(persona: Persona): Long {
        val personaId = dao.insertPersona(persona.toEntity())
        return dao.insertConversation(
            ConversationEntity(personaId = personaId, title = persona.name)
        )
    }

    /** 创建群聊：多角色 + 群名，返回会话 id */
    suspend fun createGroupConversation(title: String, personaIds: List<Long>): Long {
        val convId = dao.insertConversation(
            ConversationEntity(personaId = 0, title = title, isGroup = true)
        )
        personaIds.forEach { dao.insertParticipant(ParticipantEntity(convId, it)) }
        return convId
    }

    /** 群聊成员 */
    suspend fun participantsFor(convId: Long): List<Persona> =
        dao.participantsWithPersona(convId).map { it.persona.toPersona() }

    /** 删除会话（含消息与群成员关系）；角色本身保留 */
    suspend fun deleteConversation(convId: Long) {
        dao.deleteMessagesByConversation(convId)
        dao.deleteParticipantsByConversation(convId)
        dao.deleteConversation(convId)
    }

    /** 清空聊天记录（保留会话/群聊与成员），预览清空 */
    suspend fun clearConversationMessages(convId: Long) {
        dao.deleteMessagesByConversation(convId)
        dao.getConversation(convId)?.let { c ->
            dao.updateConversationMeta(c.id, null, System.currentTimeMillis(), c.title)
        }
    }

    /** 修改会话名称（群聊名/单聊标题） */
    suspend fun renameConversation(convId: Long, title: String) {
        dao.getConversation(convId)?.let { c ->
            dao.updateConversationMeta(c.id, c.lastMessage, System.currentTimeMillis(), title.trim().ifBlank { c.title })
        }
    }

    /** 群成员实时流 */
    fun observeGroupMembers(convId: Long): Flow<List<Persona>> =
        dao.observeParticipantsWithPersona()
            .map { rows -> rows.filter { it.conversationId == convId }.map { it.persona.toPersona() } }

    suspend fun updatePersona(persona: Persona) {
        dao.updatePersona(persona.toEntity())
        dao.getConversationByPersona(persona.id)?.let { c ->
            dao.updateConversationMeta(c.id, c.lastMessage, c.updatedAt, persona.name)
        }
    }

    suspend fun deletePersona(personaId: Long) {
        dao.getConversationByPersona(personaId)?.let { dao.deleteMessagesByConversation(it.id) }
        dao.deleteConversationsByPersona(personaId)
        dao.deleteMemoriesByPersona(personaId)
        dao.deleteParticipantsByPersona(personaId)
        dao.getPersona(personaId)?.let { dao.deletePersona(it) }
    }

    /** 首次启动向导：按情感需求生成第一个角色 */
    suspend fun createOnboardingPersona(
        userGender: String,
        needs: List<String>,
        interests: List<String>,
    ): Long {
        val persona = PersonaTemplates.onboardingPersona(userGender, needs, interests)
        return createPersonaWithConversation(persona)
    }

    /** 加载会话消息（含元数据：id/撤回/引用/时间，供消息操作使用） */
    suspend fun loadMessages(convId: Long): List<MessageEntity> = dao.messagesFor(convId)

    /** 新会话开场白（三句话速写） */
    suspend fun insertOpeningLines(convId: Long, lines: List<String>) {
        lines.forEach { dao.insertMessage(MessageEntity(conversationId = convId, role = "assistant", content = it)) }
    }

    /** 追加一条消息并更新会话预览，返回消息 id */
    suspend fun appendMessage(convId: Long, role: String, content: String, senderName: String? = null, quotedId: Long? = null): Long {
        val id = dao.insertMessage(
            MessageEntity(conversationId = convId, role = role, content = content, senderName = senderName, quotedId = quotedId)
        )
        dao.getConversation(convId)?.let { c ->
            dao.updateConversationMeta(c.id, content.take(50), System.currentTimeMillis(), c.title)
        }
        return id
    }

    /** 删除单条消息 */
    suspend fun deleteMessageById(id: Long) = dao.deleteMessage(id)

    /** 撤回消息（标记，不物理删除） */
    suspend fun recallMessage(id: Long) = dao.markMessageRecalled(id)
}
