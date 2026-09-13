package com.deepseekbuddy.agent.eval

import com.deepseekbuddy.agent.AgentConfig
import com.deepseekbuddy.agent.ChatMessage
import com.deepseekbuddy.agent.ToolCall
import com.deepseekbuddy.agent.llm.ChatResponse
import com.deepseekbuddy.agent.llm.DeepSeekClient
import com.deepseekbuddy.agent.llm.LlmClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 模型来源三选一。
 *
 * 为什么要「回放」这一档：真实 API 有随机性（temperature 默认 1.2），
 * 同一份用例今天过、明天挂，指标就没法当回归基线用。把某次真实运行的
 * 模型回复录下来回放，评测本身立刻变成**确定性**的 —— 这样改提示词、
 * 改工具 schema、改压缩策略之后，指标变化只可能来自你的改动。
 */
sealed interface EvalMode {
    val label: String

    /** 真实调用 DeepSeek。会花钱、有随机性，但反映真实水平。 */
    data class Live(val apiKey: String, val model: String = "deepseek-v4-flash") : EvalMode {
        override val label = "live"
    }

    /** 回放录制好的回复：确定性，可当回归基线。 */
    data class Replay(val file: ReplayFile, val recordPath: String? = null) : EvalMode {
        override val label = "replay"
    }

    /** 不调模型，直接给固定回复。用于验证评测流水线本身能跑通。 */
    data object NullModel : EvalMode {
        override val label = "null"
    }
}

/** 录制文件格式：每条用例一段脚本，逐轮对应模型的回复。 */
@Serializable
data class ReplayFile(
    val version: Int = 1,
    val note: String = "",
    val cases: Map<String, List<RecordedTurn>> = emptyMap(),
)

@Serializable
data class RecordedTurn(
    val text: String = "",
    val reasoning: String = "",
    val toolCalls: List<RecordedToolCall> = emptyList(),
)

@Serializable
data class RecordedToolCall(val id: String, val name: String, val argumentsJson: String)

object LlmSources {

    fun of(mode: EvalMode): LlmSource = when (mode) {
        is EvalMode.Live -> LlmSource { _, config ->
            DeepSeekClient(config.copy(apiKey = mode.apiKey, model = mode.model))
        }

        is EvalMode.Replay -> LlmSource { caseId, _ ->
            ReplayClient(mode.file.cases[caseId].orEmpty())
        }

        EvalMode.NullModel -> LlmSource { _, _ -> NullClient }
    }

    /** 按脚本逐轮返回；脚本用完后一直返回最后一条。 */
    private class ReplayClient(private val turns: List<RecordedTurn>) : LlmClient {
        private var index = 0
        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<JsonObject>,
            onDelta: (String) -> Unit,
            onReasoning: (String) -> Unit,
        ): ChatResponse {
            if (turns.isEmpty()) {
                return ChatResponse("（回放文件里没有这条用例的脚本）", "", emptyList())
            }
            val turn = turns.getOrElse(index) { turns.last() }
            index++
            onDelta(turn.text)
            if (turn.reasoning.isNotEmpty()) onReasoning(turn.reasoning)
            return ChatResponse(
                text = turn.text,
                reasoningText = turn.reasoning,
                toolCalls = turn.toolCalls.map { ToolCall(it.id, it.name, it.argumentsJson) },
            )
        }
    }

    /** 什么都不做，直接承认不会 —— 让所有用例走完流程并如实判失败。 */
    private object NullClient : LlmClient {
        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<JsonObject>,
            onDelta: (String) -> Unit,
            onReasoning: (String) -> Unit,
        ): ChatResponse = ChatResponse("（null 模式：没有接入模型）", "", emptyList())
    }
}
