package com.deepseekbuddy.app.agent.context

import com.deepseekbuddy.agent.AgentConfig
import com.deepseekbuddy.agent.ChatMessage
import com.deepseekbuddy.agent.llm.DeepSeekClient

/**
 * 滚动摘要器：历史超预算时把整段对话压缩成 200 字以内第三人称日记，
 * 结果存 conversations.summary 并注入 system prompt。
 */
class RollingSummarizer(
    private val clientFactory: (AgentConfig) -> DeepSeekClient,
) {

    suspend fun summarize(config: AgentConfig, messages: List<ChatMessage>): String {
        val client = clientFactory(config.copy(thinking = false, temperature = 0.7))
        val transcript = messages.joinToString("\n") { m ->
            val who = if (m.role == "user") "用户" else "搭子"
            "$who：${m.content ?: ""}"
        }
        val sb = StringBuilder()
        client.chat(
            listOf(
                ChatMessage("system", SUMMARY_PROMPT),
                ChatMessage("user", transcript),
            ),
            emptyList(),
            onDelta = { sb.append(it) },
        )
        return sb.toString().trim()
    }

    companion object {
        private const val SUMMARY_PROMPT =
            "你是对话压缩器。把用户和 AI 搭子的对话压缩成 200 字以内的第三人称日记，只保留情绪转折和关键事件，直接输出日记内容，不要任何解释、标题或前缀。"
    }
}
