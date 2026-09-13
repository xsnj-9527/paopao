package com.deepseekbuddy.agent.llm

import com.deepseekbuddy.agent.ChatMessage
import kotlinx.serialization.json.JsonObject

/** 一次模型调用的返回：正文、思考过程、以及模型发起的工具调用。 */
data class ChatResponse(
    val text: String,
    val reasoningText: String,
    val toolCalls: List<com.deepseekbuddy.agent.ToolCall>,
)

/**
 * 模型客户端契约。
 *
 * 原先 `AgentEngine` 的构造参数是 `(AgentConfig) -> DeepSeekClient` —— 具体类，
 * 没给任何替换余地：想跑一条评测用例就得真的打一次 API。
 * 抽出这个接口之后，评测侧注入「按脚本返回固定回复」的假客户端，
 * Agent 循环本身就能在纯 JVM 上被完整测试。
 */
interface LlmClient {
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<JsonObject>,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
    ): ChatResponse
}
