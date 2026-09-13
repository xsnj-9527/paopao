package com.deepseekbuddy.agent

/**
 * 日志抽象。
 *
 * 原来这两处直接调 `android.util.Log`，导致整个 Agent 内核无法在纯 JVM 上跑测试。
 * 抽成接口之后：Android 侧注入 Logcat 实现，评测/单测侧注入内存实现
 * —— **评测 runner 能记录完整执行轨迹，靠的就是这一层**。
 */
interface AgentLogger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String)

    /** 默认什么都不做：库被当成纯计算使用时不该产生副作用。 */
    object None : AgentLogger {
        override fun d(tag: String, message: String) = Unit
        override fun w(tag: String, message: String) = Unit
        override fun e(tag: String, message: String) = Unit
    }

    /** 收集到内存里，供测试与评测断言。 */
    class Recording : AgentLogger {
        data class Line(val level: Char, val tag: String, val message: String)

        private val lines = mutableListOf<Line>()

        val all: List<Line> get() = lines.toList()

        fun messages(): List<String> = lines.map { it.message }

        fun clear() = lines.clear()

        override fun d(tag: String, message: String) { lines += Line('D', tag, message) }
        override fun w(tag: String, message: String) { lines += Line('W', tag, message) }
        override fun e(tag: String, message: String) { lines += Line('E', tag, message) }
    }
}
