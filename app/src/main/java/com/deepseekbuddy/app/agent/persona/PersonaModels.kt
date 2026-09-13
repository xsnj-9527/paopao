package com.deepseekbuddy.app.agent.persona

import com.deepseekbuddy.app.data.local.PersonaEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 人设卡：与设计文档 5.2 对应 */
@Serializable
data class PersonaSpec(
    val tone: String = "",
    val style: String = "",
    val boundaries: List<String> = emptyList(),
    val background: String = "",
    val exampleReplies: List<String> = emptyList(),
    /** 角色心情（如：元气满满 / 有点小emo），聊天页 … 菜单可改 */
    val mood: String = "",
    /** 外貌设定（如：黑发双马尾，穿校服），聊天页 … 菜单可改 */
    val appearance: String = "",
    /** 角色主页背景：file:/path 或空（空用年龄段默认渐变） */
    val homepageBg: String = "",
    /** 角色签名（按设定自动生成，可重新生成） */
    val signature: String = "",
    /** 个性标签（AI 生成或自定义） */
    val tags: List<String> = emptyList(),
)

/** 运行时角色模型 */
data class Persona(
    val id: Long,
    val name: String,
    val gender: String,       // male / female
    val ageBand: String,      // child / youth / elder
    val relationship: String,
    val spec: PersonaSpec,
    val avatarRef: String,
)

private val json = Json { ignoreUnknownKeys = true }

fun PersonaEntity.toPersona(): Persona = Persona(
    id = id,
    name = name,
    gender = gender,
    ageBand = ageBand,
    relationship = relationship,
    spec = runCatching { json.decodeFromString<PersonaSpec>(personaJson) }
        .getOrDefault(PersonaSpec()),
    avatarRef = avatarRef,
)

fun Persona.toEntity(): PersonaEntity = PersonaEntity(
    id = id,
    name = name,
    gender = gender,
    ageBand = ageBand,
    relationship = relationship,
    personaJson = json.encodeToString(PersonaSpec.serializer(), spec),
    avatarRef = avatarRef,
)

private val AGE_BAND_LABEL = mapOf(
    "child" to "6-15 岁的小宝贝",
    "youth" to "16-28 岁的青年",
    "elder" to "29-45 岁的成熟长辈",
)

private val GENDER_LABEL = mapOf(
    "male" to "男生",
    "female" to "女生",
)

/** 构建该角色的系统提示词（含规则段） */
fun Persona.buildSystemPrompt(): String = buildString {
    append("你是「$name」，一位${GENDER_LABEL[gender] ?: "伙伴"}，${AGE_BAND_LABEL[ageBand] ?: ageBand}，与用户的关系是「$relationship」。\n")
    append("\n【性格】${spec.tone}\n")
    append("【说话风格】${spec.style}\n")
    append("【背景】${spec.background}\n")
    if (spec.mood.isNotBlank()) append("【心情】${spec.mood}\n")
    if (spec.appearance.isNotBlank()) append("【外貌】${spec.appearance}\n")
    if (spec.boundaries.isNotEmpty()) {
        append("【边界】\n")
        spec.boundaries.forEach { append("- $it\n") }
    }
    if (spec.exampleReplies.isNotEmpty()) {
        append("【示例回复】\n")
        spec.exampleReplies.forEach { append("- \"$it\"\n") }
    }
    append("""
        |【规则】
        |- 全程中文，口语化，像真人一样说话，不要用列表和官腔
        |- 始终保持在角色里，不要自称 AI、模型或"作为一个人工智能"
        |- 像真人聊天：单条回复不超过 3 行；想说的多就拆成几条短消息（每条之间用空行分隔），不要写小作文
        |- 不确定的事明说不知道，不要编造
        |- 用户要设置提醒/闹钟时调用 create_reminder；问时间时调用 get_time，以工具返回为准
        |- 用户说「记住…/别忘了…」这类话时，调用 remember_fact 记下来，并简短确认
        |- 调用工具后以工具返回结果为准，不要编造执行结果
    """.trimMargin())
}
