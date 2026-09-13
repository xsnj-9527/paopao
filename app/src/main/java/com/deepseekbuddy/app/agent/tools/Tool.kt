package com.deepseekbuddy.app.agent.tools

import kotlinx.serialization.json.JsonObject

enum class RiskLevel { NONE, CONFIRM, FORBIDDEN }

data class ToolResult(val success: Boolean, val message: String) {
    companion object {
        fun ok(message: String) = ToolResult(true, message)
        fun fail(message: String) = ToolResult(false, message)
    }
}

/** 工具执行上下文：当前会话所属角色等 */
data class ToolContext(val personaId: Long)

/**
 * 工具接口：所有能力（提醒/便签/记忆/无障碍操控等）以同一契约接入。
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject   // JSON Schema（OpenAI function 格式）
    val riskLevel: RiskLevel

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}
