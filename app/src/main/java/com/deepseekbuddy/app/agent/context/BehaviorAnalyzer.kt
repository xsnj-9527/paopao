package com.deepseekbuddy.app.agent.context

import com.deepseekbuddy.agent.AgentConfig
import com.deepseekbuddy.agent.ChatMessage
import com.deepseekbuddy.agent.llm.DeepSeekClient
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.data.local.AppDao
import java.time.Instant
import java.time.ZoneId

/**
 * 行为画像：App 退后台时（节流 24h）轻量分析——高频情绪词/喜好/作息规律
 * → 编译成几行「行为准则」写入系统提示词；同时学习常聊时段（时空胶囊用）。
 */
class BehaviorAnalyzer(
    private val dao: AppDao,
    private val settings: SettingsStore,
    private val clientFactory: (AgentConfig) -> DeepSeekClient,
) {

    suspend fun analyzeIfDue() {
        if (settings.privacyModeValue()) return
        val now = System.currentTimeMillis()
        if (now - settings.lastAnalysisAtValue() < THROTTLE_MS) return

        val userMessages = dao.recentUserMessages(40)
        if (userMessages.size < MIN_MESSAGES) return

        val config = settings.currentConfig()
        if (config.apiKey.isBlank()) return

        val client = clientFactory(config.copy(thinking = false, temperature = 0.6))
        val sb = StringBuilder()
        client.chat(
            listOf(
                ChatMessage("system", ANALYZE_PROMPT),
                ChatMessage("user", userMessages.joinToString("\n")),
            ),
            emptyList(),
            onDelta = { sb.append(it) },
        )
        val rules = sb.toString().trim()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(5)
            .joinToString("\n")
        if (rules.isNotBlank()) {
            settings.setBehaviorRules(rules)
            settings.setLastAnalysisAt(now)

            // 学习常聊时段（用户消息小时直方图 Top4）
            val hours = dao.recentUserTimestamps()
                .map { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).hour }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(4)
                .map { it.key }
                .sorted()
            settings.setActiveHours(hours.joinToString(","))
        }
    }

    companion object {
        private const val THROTTLE_MS = 24 * 60 * 60 * 1000L
        private const val MIN_MESSAGES = 15
        private const val ANALYZE_PROMPT =
            "你是用户行为分析师。根据用户的聊天内容，总结用户的喜好、高频情绪和作息规律，" +
            "输出 3-5 条行为准则，每条一行，用中文，不要编号以外的格式，不要解释。"
    }
}
