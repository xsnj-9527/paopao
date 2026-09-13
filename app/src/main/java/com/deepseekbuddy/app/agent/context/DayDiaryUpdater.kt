package com.deepseekbuddy.app.agent.context

import com.deepseekbuddy.app.agent.AgentConfig
import com.deepseekbuddy.app.agent.ChatMessage
import com.deepseekbuddy.app.agent.llm.DeepSeekClient
import com.deepseekbuddy.app.data.DiaryRepository
import java.time.LocalDate

/**
 * 日历日记：每 20 轮把「当天已有日记 + 新对话」合并成 200 字以内第三人称日记，
 * 存 day_summaries，供 3 天注入与时空胶囊使用。
 */
class DayDiaryUpdater(
    private val diaries: DiaryRepository,
    private val clientFactory: (AgentConfig) -> DeepSeekClient,
) {

    suspend fun update(config: AgentConfig, recent: List<ChatMessage>) {
        val today = LocalDate.now().toString()
        val prev = diaries.getByDate(today)?.content.orEmpty()
        val transcript = recent.joinToString("\n") { m ->
            val who = if (m.role == "user") "用户" else "搭子"
            "$who：${m.content ?: ""}"
        }
        val client = clientFactory(config.copy(thinking = false, temperature = 0.7))
        val sb = StringBuilder()
        client.chat(
            listOf(
                ChatMessage("system", MERGE_PROMPT),
                ChatMessage("user", "已有日记：\n${prev.ifBlank { "（无）" }}\n\n新对话：\n$transcript"),
            ),
            emptyList(),
            onDelta = { sb.append(it) },
        )
        val merged = sb.toString().trim()
        if (merged.isNotBlank()) diaries.upsert(today, merged)
    }

    companion object {
        private const val MERGE_PROMPT =
            "你是日记员。把「已有日记」和「新对话」合并更新成一篇 200 字以内的第三人称日记，只保留情绪转折和关键事件，直接输出日记内容，不要任何解释、标题或前缀。"
    }
}
