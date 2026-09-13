package com.deepseekbuddy.app.agent

/** 与 DeepSeek API 交互的消息体（OpenAI 兼容格式） */
data class ChatMessage(
    val role: String,              // system / user / assistant / tool
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
)

/** 模型发起的工具调用（assistant 消息内） */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** 一次请求的完整配置（每次发送前从 SettingsStore 读取） */
data class AgentConfig(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String,
    val model: String = "deepseek-v4-flash",
    val temperature: Double = 1.2,
    val thinking: Boolean = false,   // V4 思考模式：默认关（快），复杂任务可开
)
