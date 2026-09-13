package com.deepseekbuddy.app.agent.tools

import com.deepseekbuddy.app.data.MemoryRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 显式记忆工具：用户说「记住…/别忘了…」时由模型调用。
 * 记忆按角色隔离——只记入当前角色（像真朋友一样，只记得你告诉 TA 的事）。
 */
class RememberFactTool(private val memories: MemoryRepository) : Tool {

    override val name = "remember_fact"
    override val description =
        "用户明确要求记住某件事时调用（如「记住我养猫了」「以后别忘了提醒我恐高」）。content 是被记住的事实；category 可选：偏好/事实/关系/任务；importance 是重要度 1-5，默认 3。"
    override val riskLevel = RiskLevel.NONE

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("content") {
                put("type", "string")
                put("description", "要记住的事实内容，完整一句话")
            }
            putJsonObject("category") {
                put("type", "string")
                put("description", "分类：偏好/事实/关系/任务")
            }
            putJsonObject("importance") {
                put("type", "integer")
                put("description", "重要度 1-5，默认 3")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("content")) }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (ctx.personaId <= 0) {
            return ToolResult.fail("群聊里先不记啦，私聊的时候告诉我，我会好好记住的")
        }
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.fail("缺少 content 参数")
        val category = args["category"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in listOf("偏好", "事实", "关系", "任务") } ?: "事实"
        val importance = args["importance"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 5) ?: 3
        memories.add(ctx.personaId, content, category, importance)
        return ToolResult.ok("已记住（$category）：$content")
    }
}
