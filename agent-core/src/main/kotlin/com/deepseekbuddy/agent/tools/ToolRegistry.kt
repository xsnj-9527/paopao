package com.deepseekbuddy.agent.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ToolRegistry(tools: List<Tool>) {

    private val byName = tools.associateBy { it.name }

    val all: List<Tool> get() = byName.values.toList()

    /** 转换为 DeepSeek function calling 的 tools 数组 */
    fun toOpenAiSchema(): List<JsonObject> = byName.values.map { tool ->
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
            }
        }
    }

    suspend fun execute(name: String, argumentsJson: String, ctx: ToolContext): ToolResult {
        val tool = byName[name] ?: return ToolResult.invalid("未知工具：$name")
        val args = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        return try {
            tool.execute(args, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.fail("工具执行异常：${e.message}")
        }
    }

    /** 查询工具风险级别（引擎确认机制用） */
    fun riskOf(name: String): RiskLevel = byName[name]?.riskLevel ?: RiskLevel.NONE
}
