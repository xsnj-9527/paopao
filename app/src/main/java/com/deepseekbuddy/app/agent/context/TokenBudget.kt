package com.deepseekbuddy.app.agent.context

import com.deepseekbuddy.app.agent.ChatMessage

/**
 * token 预算：中文约 1 token/1.5 字，按 2 字符/token 保守估算。
 * 历史超阈值 → 滚动摘要压缩 → 裁剪保留最近部分。
 */
object TokenBudget {

    /** 历史估算 token 超此值触发压缩 */
    const val SUMMARY_TRIGGER = 6000

    /** 压缩后保留的最近历史 token 量 */
    const val KEEP_AFTER_TRIM = 3000

    fun estimate(text: String): Int = (text.length / 2) + 1

    fun estimate(messages: List<ChatMessage>): Int = messages.sumOf { estimate(it.content ?: "") }

    /** 从尾部保留最近消息，直到接近预算 */
    fun trimHistory(messages: List<ChatMessage>): List<ChatMessage> {
        var tokens = 0
        val tail = mutableListOf<ChatMessage>()
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            tail.add(0, m)
            tokens += estimate(m.content ?: "")
            if (tokens >= KEEP_AFTER_TRIM) break
        }
        return tail
    }
}
