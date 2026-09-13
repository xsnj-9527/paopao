package com.deepseekbuddy.agent.tools

import com.deepseekbuddy.agent.ports.ReminderGateway
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 创建提醒：AlarmManager + 通知 */
class ReminderTool(
    private val scheduler: ReminderGateway,
    /** 当前时间源。注入之后评测可以冻结时间,让「20 分钟后」这类用例可回归。 */
    private val now: () -> Long = System::currentTimeMillis,
) : Tool {

    override val name = "create_reminder"
    override val description =
        "为用户设置一条到点提醒（应用内通知）。title 是提醒内容；triggerAt 是触发时间，必须为 ISO 8601 格式（如 2026-08-13T20:00:00+08:00 或 2026-08-13T20:00:00Z）。"
    override val riskLevel = RiskLevel.NONE

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") {
                put("type", "string")
                put("description", "提醒内容，一句话说清楚，如：晚上8点开会")
            }
            putJsonObject("triggerAt") {
                put("type", "string")
                put("description", "触发时间，ISO 8601 格式")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("title")); add(JsonPrimitive("triggerAt")) }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.invalid("缺少 title 参数")
        val triggerAt = args["triggerAt"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.invalid("缺少 triggerAt 参数")
        val millis = parseTime(triggerAt)
            ?: return ToolResult.invalid("无法解析时间：$triggerAt，请使用 ISO 8601 格式（如 2026-08-13T20:00:00+08:00）")
        if (millis <= now()) {
            return ToolResult.invalid("提醒时间必须晚于当前时间")
        }
        scheduler.schedule(millis, title)
        val timeStr = DateTimeFormatter.ofPattern("M月d日 HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
        return ToolResult.ok("已设置提醒「$title」，$timeStr")
    }

    private fun parseTime(s: String): Long? =
        runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                .getOrNull()
}
