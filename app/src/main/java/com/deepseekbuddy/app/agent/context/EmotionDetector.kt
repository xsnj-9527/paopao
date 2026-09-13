package com.deepseekbuddy.app.agent.context

import androidx.compose.ui.graphics.Color

/**
 * 本地轻量情绪检测（词典打分，零延迟零成本）：
 * 情绪 → 聊天背景「呼吸光晕」颜色。
 */
object EmotionDetector {

    enum class Emotion { HAPPY, SAD_ANXIOUS, ANGRY, CALM }

    private val happyWords = listOf(
        "哈哈", "开心", "高兴", "太好了", "好耶", "喜欢", "棒", "笑死", "嘿嘿", "嘻嘻",
        "爱了", "太爽", "舒服", "😄", "😆", "🥰", "😊", "🎉", "👍", "❤️",
    )

    private val sadWords = listOf(
        "难过", "伤心", "焦虑", "压力", "好烦", "烦死", "累了", "好累", "哭", "失眠",
        "孤独", "委屈", "心累", "emo", "😭", "😢", "😞", "💔",
    )

    private val angryWords = listOf(
        "生气", "气死", "讨厌", "无语", "🔥", "😡", "🤬",
    )

    fun detect(text: String): Emotion {
        var h = 0
        var s = 0
        var a = 0
        happyWords.forEach { if (text.contains(it)) h++ }
        sadWords.forEach { if (text.contains(it)) s++ }
        angryWords.forEach { if (text.contains(it)) a++ }
        return when {
            a > h && a >= s -> Emotion.ANGRY
            s > h -> Emotion.SAD_ANXIOUS
            h > 0 -> Emotion.HAPPY
            else -> Emotion.CALM
        }
    }

    /** 焦虑/低落 → 冷蓝冷静；开心 → 暖黄；生气 → 红；平静 → 无光晕 */
    fun glowColor(emotion: Emotion): Color? = when (emotion) {
        Emotion.HAPPY -> Color(0xFFFFD166)
        Emotion.SAD_ANXIOUS -> Color(0xFF6C8EFF)
        Emotion.ANGRY -> Color(0xFFFF6B6B)
        Emotion.CALM -> null
    }
}
