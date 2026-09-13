package com.deepseekbuddy.agent

import com.deepseekbuddy.agent.llm.ChatResponse
import com.deepseekbuddy.agent.llm.LlmClient
import com.deepseekbuddy.agent.ports.MemoryStore
import com.deepseekbuddy.agent.ports.NoteStore
import com.deepseekbuddy.agent.ports.ReminderGateway
import com.deepseekbuddy.agent.tools.Tool
import com.deepseekbuddy.agent.tools.ToolContext
import com.deepseekbuddy.agent.tools.ToolResult
import com.deepseekbuddy.agent.tools.RiskLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 测试替身。
 *
 * 这些类存在的本身就是「抽库成功」的证据：抽库之前，要跑一次 Agent 循环
 * 必须有 Room、AlarmManager 和一个真的 DeepSeek API Key。
 */

/** 按脚本逐轮返回的假模型客户端。脚本用完之后一直返回最后一条。 */
class FakeLlmClient(private val script: List<ChatResponse>) : LlmClient {
    private var index = 0
    val calls = mutableListOf<Call>()

    data class Call(val messages: List<ChatMessage>, val tools: List<JsonObject>)

    val callCount: Int get() = calls.size

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<JsonObject>,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
    ): ChatResponse {
        calls += Call(messages.toList(), tools.toList())
        val response = script.getOrElse(index) { script.last() }
        index++
        onDelta(response.text)
        if (response.reasoningText.isNotEmpty()) onReasoning(response.reasoningText)
        return response
    }

    companion object {
        fun textOnly(text: String) = FakeLlmClient(listOf(ChatResponse(text, "", emptyList())))
        fun toolThenText(toolName: String, argsJson: String, finalText: String) = FakeLlmClient(
            listOf(
                ChatResponse("", "", listOf(ToolCall("call_1", toolName, argsJson))),
                ChatResponse(finalText, "", emptyList()),
            ),
        )
    }
}

class InMemoryNoteStore : NoteStore {
    data class Note(val title: String, val content: String)
    val notes = mutableListOf<Note>()
    override suspend fun add(title: String, content: String) { notes += Note(title, content) }
}

class InMemoryMemoryStore : MemoryStore {
    data class Memory(val personaId: Long, val content: String, val category: String, val importance: Int)
    val memories = mutableListOf<Memory>()
    override suspend fun add(personaId: Long, content: String, category: String, importance: Int) {
        memories += Memory(personaId, content, category, importance)
    }
}

class RecordingReminderGateway : ReminderGateway {
    data class Scheduled(val triggerAtMillis: Long, val title: String)
    val scheduled = mutableListOf<Scheduled>()
    override fun schedule(triggerAtMillis: Long, title: String) {
        scheduled += Scheduled(triggerAtMillis, title)
    }
}

/** 可配置行为的测试工具。 */
class ProbeTool(
    override val name: String,
    override val riskLevel: RiskLevel = RiskLevel.NONE,
    private val result: ToolResult = ToolResult.ok("ok"),
    private val throwOnExecute: Boolean = false,
) : Tool {
    override val description = "probe tool"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
    }

    var executions = 0
        private set

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        executions++
        if (throwOnExecute) throw IllegalStateException("工具内部炸了")
        return result
    }
}

/** 记录一次运行的完整回调序列 —— 评测 runner 要的 trajectory 就是从这些回调上采的。 */
class TrajectoryRecorder {
    sealed interface Event {
        data class Delta(val text: String) : Event
        data class ToolCallEvent(val name: String, val argumentsJson: String) : Event
        data class ToolResultEvent(val success: Boolean, val message: String) : Event
        data class ConfirmRequested(val name: String, val allowed: Boolean) : Event
    }

    val events = mutableListOf<Event>()

    fun onDelta(text: String) { events += Event.Delta(text) }
    fun onToolCall(name: String, args: String) { events += Event.ToolCallEvent(name, args) }
    fun onToolResult(success: Boolean, message: String) { events += Event.ToolResultEvent(success, message) }
    fun onConfirm(name: String, allowed: Boolean) { events += Event.ConfirmRequested(name, allowed) }

    fun toolNames(): List<String> = events.filterIsInstance<Event.ToolCallEvent>().map { it.name }
    fun toolResults(): List<Event.ToolResultEvent> = events.filterIsInstance<Event.ToolResultEvent>()
}
