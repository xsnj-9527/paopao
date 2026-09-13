package com.deepseekbuddy.agent.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 用例模型：`evals/cases/` 下 JSON 的结构映射。
 *
 * 用例是数据，不是代码 —— 加一条用例不需要改 runner。
 */

@Serializable
data class CaseFile(
    val version: Int = 1,
    val category: String,
    val description: String = "",
    /** 冻结时间：所有相对时间用例都以此为基准，保证可回归。 */
    val now: String,
    val nowNote: String = "",
    val cases: List<EvalCase> = emptyList(),
)

@Serializable
data class EvalCase(
    val id: String,
    val difficulty: String = "single",
    val prompt: String,
    val history: List<HistoryTurn> = emptyList(),
    val expect: Expect,
    val rationale: String = "",
)

@Serializable
data class HistoryTurn(val role: String, val content: String)

@Serializable
data class Expect(
    /** 期望的工具调用序列（严格有序、严格数量）。空数组表示「不该调任何工具」。 */
    val tools: List<String> = emptyList(),
    /** 出现即算失败的**反向**断言，通常比 tools 更能暴露误触发。 */
    val forbidden: List<String> = emptyList(),
    /** toolName -> argName -> 匹配器 */
    val args: Map<String, Map<String, ArgMatcher>> = emptyMap(),
    /** 最终回答里必须出现的关键词（任一命中即可）。 */
    val answerContains: List<String> = emptyList(),
)

/**
 * 参数匹配器。字段全可空，用哪个就填哪个 —— 一条用例只写它真正在意的那部分，
 * 避免把用例写死成「参数必须逐字节相等」而变得脆弱。
 */
@Serializable
data class ArgMatcher(
    val nonEmpty: Boolean? = null,
    val equalsAny: List<String>? = null,
    val containsAny: List<String>? = null,
    @SerialName("intRange") val intRange: List<Int>? = null,
    /** ISO 8601 校验；同时给了 at 就要求瞬时点完全相等。 */
    val iso8601: Boolean? = null,
    val at: String? = null,
)

internal val evalJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
