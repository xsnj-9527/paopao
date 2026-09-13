package com.deepseekbuddy.agent.tools

import kotlinx.serialization.json.JsonObject

enum class RiskLevel { NONE, CONFIRM, FORBIDDEN }

/**
 * 失败类型。
 *
 * 评测的失败归因必须区分这两种 —— 否则「工具正确地拒绝了一个非法参数」
 * 会被误算成「工具坏了」，进而去修一个根本没坏的组件。
 */
enum class FailureKind {
    /** 模型给的输入不合法（缺参、算错、越界、选择了被禁用的操作）——是模型的锅。 */
    INVALID_INPUT,

    /** 工具自身出错（异常、依赖不可用）——是工具的锅。 */
    INTERNAL,
}

data class ToolResult(
    val success: Boolean,
    val message: String,
    val failureKind: FailureKind? = null,
) {
    companion object {
        fun ok(message: String) = ToolResult(true, message)

        /** 输入不合法。参数校验、越界、策略拒绝都走这里。 */
        fun invalid(message: String) = ToolResult(false, message, FailureKind.INVALID_INPUT)

        /** 工具自身出错。只有真的坏了才用这个。 */
        fun fail(message: String) = ToolResult(false, message, FailureKind.INTERNAL)
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
