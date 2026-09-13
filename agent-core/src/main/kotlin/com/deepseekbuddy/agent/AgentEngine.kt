package com.deepseekbuddy.agent

import com.deepseekbuddy.agent.llm.LlmClient
import com.deepseekbuddy.agent.tools.RiskLevel
import com.deepseekbuddy.agent.tools.ToolContext
import com.deepseekbuddy.agent.tools.ToolRegistry
import com.deepseekbuddy.agent.tools.ToolResult

/**
 * Agent 循环（harness 心脏）：
 * 拼装消息 → 请求模型 → 若发起工具调用则执行并回填 → 重入，直到产出最终回答。
 * systemPrompt 由角色（Persona）构建后从外部传入。
 */
class AgentEngine(
    private val registry: ToolRegistry,
    private val clientFactory: (AgentConfig) -> LlmClient,
    /** 日志出口。默认吞掉——库被当纯计算用时不该有副作用。 */
    private val log: AgentLogger = AgentLogger.None,
) {

    companion object {
        const val MAX_TOOL_ITERATIONS = 5
        private const val TAG = "AgentEngine"
    }

    suspend fun run(
        config: AgentConfig,
        history: List<ChatMessage>,
        userText: String,
        systemPrompt: String,
        personaId: Long,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onConfirmRequest: suspend (name: String, argumentsJson: String) -> Boolean,
        onToolCall: (name: String, argumentsJson: String) -> Unit,
        onToolResult: (success: Boolean, message: String) -> Unit,
    ): String {
        val client = clientFactory(config)
        val ctx = ToolContext(personaId)
        val messages = mutableListOf(
            ChatMessage("system", systemPrompt)
        ).apply {
            addAll(history)
            add(ChatMessage("user", userText))
        }

        var finalText = ""
        for (iteration in 0..MAX_TOOL_ITERATIONS) {
            val resp = client.chat(messages, registry.toOpenAiSchema(), onDelta, onReasoning)
            finalText += resp.text
            log.d(TAG, "iteration=$iteration textLen=${resp.text.length} toolCalls=${resp.toolCalls.size}")
            if (resp.toolCalls.isEmpty()) return finalText

            for (tc in resp.toolCalls) {
                onToolCall(tc.name, tc.argumentsJson)
                // 确认机制：FORBIDDEN 直接拦截；CONFIRM 挂起等待用户，拒绝则回填拒绝结果
                val result = when (registry.riskOf(tc.name)) {
                    RiskLevel.FORBIDDEN -> ToolResult.fail("该操作已被用户禁用")
                    RiskLevel.CONFIRM -> {
                        val allowed = onConfirmRequest(tc.name, tc.argumentsJson)
                        if (allowed) registry.execute(tc.name, tc.argumentsJson, ctx)
                        else ToolResult.fail("用户拒绝了该操作")
                    }
                    else -> registry.execute(tc.name, tc.argumentsJson, ctx)
                }
                log.d(TAG, "tool '${tc.name}' -> success=${result.success}: ${result.message}")
                onToolResult(result.success, result.message)
                messages += ChatMessage("assistant", content = null, toolCalls = listOf(tc))
                messages += ChatMessage(
                    "tool",
                    content = if (result.success) "成功：${result.message}" else "失败：${result.message}",
                    toolCallId = tc.id,
                )
            }
        }

        val capNote = "\n\n（我在这个任务上绕了太多圈，请再说具体些～）"
        onDelta(capNote)
        return finalText + capNote
    }
}
