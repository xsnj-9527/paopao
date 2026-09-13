package com.deepseekbuddy.agent.tools

import com.deepseekbuddy.agent.ports.NoteStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 创建便签/笔记 */
class NoteTool(private val notes: NoteStore) : Tool {

    override val name = "create_note"
    override val description =
        "为用户创建一条便签/笔记，用于记录需要保存的信息（如清单、灵感、地址等）。title 是标题；content 是正文。"
    override val riskLevel = RiskLevel.NONE

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") {
                put("type", "string")
                put("description", "便签标题，简短")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "便签正文")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("title")); add(JsonPrimitive("content")) }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.fail("缺少 title 参数")
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        notes.add(title, content)
        return ToolResult.ok("已保存便签「$title」")
    }
}
