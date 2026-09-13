package com.deepseekbuddy.agent.eval

import com.deepseekbuddy.agent.AgentConfig
import com.deepseekbuddy.agent.AgentEngine
import com.deepseekbuddy.agent.ChatMessage
import com.deepseekbuddy.agent.InMemoryMemoryStore
import com.deepseekbuddy.agent.InMemoryNoteStore
import com.deepseekbuddy.agent.RecordingReminderGateway
import com.deepseekbuddy.agent.llm.ChatResponse
import com.deepseekbuddy.agent.llm.LlmClient
import com.deepseekbuddy.agent.tools.FailureKind
import com.deepseekbuddy.agent.tools.NoteTool
import com.deepseekbuddy.agent.tools.RememberFactTool
import com.deepseekbuddy.agent.tools.ReminderTool
import com.deepseekbuddy.agent.tools.TimeTool
import com.deepseekbuddy.agent.tools.ToolRegistry
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId
import java.time.ZonedDateTime

/** 一次工具调用的完整记录 —— 失败归因就是在这上面做的。 */
data class ToolCallRecord(
    val name: String,
    val argumentsJson: String,
    val success: Boolean,
    val message: String,
    /** 失败类型。归因要靠它区分「工具坏了」和「模型给错了输入」。 */
    val failureKind: FailureKind? = null,
)

/** 一条用例跑完之后的全部可观测结果。 */
data class Trajectory(
    val caseId: String,
    val category: String,
    val toolCalls: List<ToolCallRecord>,
    val finalAnswer: String,
    /** 模型请求次数 = Agent 循环轮次。 */
    val rounds: Int,
    /** 估算 token（与 App 侧 TokenBudget 同一套 2 字符/token 口径）。 */
    val estimatedTokens: Int,
    val error: String? = null,
    /** 本轮跑完之后应用侧真实落库的内容，用于人工核对。 */
    val sideEffects: SideEffects = SideEffects(),
)

data class SideEffects(
    val notes: List<Pair<String, String>> = emptyList(),
    val memories: List<Triple<String, String, Int>> = emptyList(),
    val reminders: List<Pair<Long, String>> = emptyList(),
)

/** 每个用例单独取一个模型客户端：回放模式下按用例给不同脚本。 */
fun interface LlmSource {
    fun clientFor(caseId: String, config: AgentConfig): LlmClient
}

/** 统计调用次数与估算 token，并把内部异常转成可归因的错误串。 */
private class CountingClient(private val inner: LlmClient) : LlmClient {
    var calls = 0
    var estimatedTokens = 0

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<JsonObject>,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
    ): ChatResponse {
        calls++
        // 与 TokenBudget.estimate 同一口径：中文约 2 字符/token。
        estimatedTokens += messages.sumOf { (it.content?.length ?: 0) / 2 + 1 }
        estimatedTokens += tools.toString().length / 2
        return inner.chat(messages, tools, onDelta, onReasoning)
    }
}

object EvalHarness {

    private const val SYSTEM_PROMPT = "你是泡泡，一个安卓上的 AI 助手。用户让你做什么就做什么。"

    fun parseNow(raw: String): ZonedDateTime = ZonedDateTime.parse(raw)

    /**
     * 跑一条用例。
     *
     * 时钟、存储、调度器全部注入 —— 所以同一份用例在任何机器、任何时刻跑出来的
     * 结果都一致。这是「可回归」的前提。
     */
    suspend fun runCase(
        case: EvalCase,
        category: String,
        nowRaw: String,
        llmSource: LlmSource,
        config: AgentConfig,
    ): Trajectory {
        val now = parseNow(nowRaw)
        val zone: ZoneId = now.zone
        val nowMillis = now.toInstant().toEpochMilli()

        val notes = InMemoryNoteStore()
        val memories = InMemoryMemoryStore()
        val reminders = RecordingReminderGateway()

        val registry = ToolRegistry(
            listOf(
                ReminderTool(reminders, now = { nowMillis }),
                TimeTool { now },
                RememberFactTool(memories),
                NoteTool(notes),
            ),
        )

        val counting = CountingClient(llmSource.clientFor(case.id, config))
        val engine = AgentEngine(registry, { counting }, com.deepseekbuddy.agent.AgentLogger.None)

        val calls = mutableListOf<ToolCallRecord>()
        var error: String? = null
        var answer = ""

        try {
            answer = engine.run(
                config = config,
                history = case.history.map { ChatMessage(it.role, it.content) },
                userText = case.prompt,
                systemPrompt = SYSTEM_PROMPT,
                personaId = 1L,
                onDelta = {},
                onToolCall = { name, argsJson -> calls += ToolCallRecord(name, argsJson, false, "") },
                onToolResult = { result ->
                    val last = calls.lastOrNull()
                    if (last != null) {
                        calls[calls.lastIndex] = last.copy(
                            success = result.success,
                            message = result.message,
                            failureKind = result.failureKind,
                        )
                    }
                },
                onConfirmRequest = { _, _ -> true },
            )
        } catch (e: Exception) {
            error = "${e::class.simpleName}: ${e.message}"
        }

        return Trajectory(
            caseId = case.id,
            category = category,
            toolCalls = calls,
            finalAnswer = answer,
            rounds = counting.calls,
            estimatedTokens = counting.estimatedTokens,
            error = error,
            sideEffects = SideEffects(
                notes = notes.notes.map { it.title to it.content },
                memories = memories.memories.map { Triple(it.content, it.category, it.importance) },
                reminders = reminders.scheduled.map { it.triggerAtMillis to it.title },
            ),
        )
    }
}
