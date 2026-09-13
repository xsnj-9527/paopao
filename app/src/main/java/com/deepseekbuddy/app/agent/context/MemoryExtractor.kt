package com.deepseekbuddy.app.agent.context

import com.deepseekbuddy.agent.AgentConfig
import com.deepseekbuddy.agent.ChatMessage
import com.deepseekbuddy.agent.llm.DeepSeekClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ExtractedFact(
    val content: String,
    val category: String,
    val importance: Int,
)

/**
 * 记忆自动抽取：每 10 轮对最近对话提取关于用户的事实 → JSON。
 * 隐私模式下不运行（由调用方判断）。
 */
class MemoryExtractor(
    private val clientFactory: (AgentConfig) -> DeepSeekClient,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun extract(config: AgentConfig, recent: List<ChatMessage>): List<ExtractedFact> {
        val transcript = recent.joinToString("\n") { m ->
            val who = if (m.role == "user") "用户" else "搭子"
            "$who：${m.content ?: ""}"
        }
        val client = clientFactory(config.copy(thinking = false, temperature = 0.3))
        val sb = StringBuilder()
        client.chat(
            listOf(
                ChatMessage("system", EXTRACT_PROMPT),
                ChatMessage("user", transcript),
            ),
            emptyList(),
            onDelta = { sb.append(it) },
        )
        return parse(sb.toString())
    }

    private fun parse(raw: String): List<ExtractedFact> = runCatching {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        json.decodeFromString<List<FactJson>>(raw.substring(start, end + 1))
            .mapNotNull { f ->
                val content = f.content.trim()
                if (content.isEmpty()) null
                else ExtractedFact(content, f.category, f.importance.coerceIn(1, 5))
            }
    }.getOrDefault(emptyList())

    @Serializable
    private data class FactJson(
        val content: String = "",
        val category: String = "事实",
        val importance: Int = 3,
    )

    companion object {
        private const val EXTRACT_PROMPT =
            "从以下对话中提取值得长期记住的关于用户的事实（偏好、关系、个人信息、承诺等）。只输出 JSON 数组，不要任何其他内容：" +
            "[{\"content\":\"事实内容\",\"category\":\"偏好|事实|关系|任务\",\"importance\":1到5}]。没有值得记住的则输出 []。"
    }
}
