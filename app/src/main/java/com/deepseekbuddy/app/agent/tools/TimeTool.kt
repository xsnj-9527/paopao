package com.deepseekbuddy.app.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 返回当前本地时间，防止模型时间幻觉 */
class TimeTool : Tool {

    override val name = "get_time"
    override val description = "获取当前本地日期和时间。用户问时间/日期/今天是几号时调用，不要自己推算。"
    override val riskLevel = RiskLevel.NONE

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
        putJsonArray("required") { }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val now = ZonedDateTime.now()
        val text = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINESE).format(now)
        return ToolResult.ok("当前时间是 $text")
    }
}
