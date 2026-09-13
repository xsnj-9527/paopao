package com.deepseekbuddy.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 角色（人格）表：一个角色 = 一套人设 + 一个专属会话 */
@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val gender: String,            // male / female
    val ageBand: String,           // child / youth / elder
    val relationship: String,      // 爸爸/妈妈/哥哥/姐姐/好朋友/损友/学习搭子/树洞/如父/如母/亦师亦友
    val personaJson: String,       // PersonaSpec 序列化：tone/style/boundaries/background/exampleReplies
    val avatarRef: String = "🐱",  // 预设 emoji 头像（自定义头像阶段 2.5 引入）
    val createdAt: Long = System.currentTimeMillis(),
)

/** 会话表：单聊 = 一个角色；群聊 = 多角色（personaId=0 + isGroup，成员在 participants 表） */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personaId: Long = 0,          // 单聊角色 id；群聊为 0
    val title: String,
    val lastMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    /** 本会话专属聊天背景：file:/path 或空（在聊天页 … 菜单设置） */
    val bgRef: String = "",
    /** 滚动摘要：历史超预算时由 LLM 压缩，注入 system prompt（阶段 3） */
    val summary: String = "",
    /** 是否群聊 */
    val isGroup: Boolean = false,
)

/** 群聊成员表 */
@Entity(tableName = "conversation_participants", primaryKeys = ["conversationId", "personaId"])
data class ParticipantEntity(
    val conversationId: Long,
    val personaId: Long,
)

/** 消息表：只持久化 user/assistant 可见消息（工具调用过程不持久化，重放时由引擎重建） */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,              // user / assistant
    val content: String,
    /** 群聊中 assistant 消息的发言者（单聊为 null） */
    val senderName: String? = null,
    /** 已撤回（3 分钟内可撤） */
    val recalled: Boolean = false,
    /** 引用的消息 id（用户消息可带引用） */
    val quotedId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 收藏表：多选消息收藏 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val senderName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 长期记忆表（按角色隔离：每个角色只记得用户亲口告诉 TA 的事，更接近真实体验）。
 * 写入途径：显式「记住…」指令（remember_fact 工具）；自动抽取在阶段 4。
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personaId: Long? = null,   // null = 旧版本遗留数据（不注入任何角色）
    val content: String,
    val category: String = "事实",  // 偏好 / 事实 / 关系 / 任务
    val importance: Int = 3,       // 1-5
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
    val sourceConvId: Long? = null,
)

/** 便签表（全局，非角色隔离——与提醒类似，是用户的工具产物） */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 日历日记表：每天一篇 200 字以内第三人称日记（情绪转折 + 关键事件） */
@Entity(tableName = "day_summaries")
data class DaySummaryEntity(
    @PrimaryKey val date: String,   // yyyy-MM-dd
    val content: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
