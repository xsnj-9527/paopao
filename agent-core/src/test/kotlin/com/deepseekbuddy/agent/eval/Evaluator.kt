package com.deepseekbuddy.agent.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 失败归因的六个格子。改法各不相同，所以必须分开。 */
enum class Attribution(val label: String, val howToFix: String) {
    MODEL_TOOL_CHOICE(
        "模型问题 · 工具选择",
        "改系统提示词、改工具 description，或换模型。**不要动用例。**",
    ),
    MODEL_ARGUMENTS(
        "模型问题 · 参数编造/算错",
        "先看参数描述是否歧义（如 triggerAt 没说是 ISO 8601）；描述没问题才归给模型能力。",
    ),
    MODEL_INCOMPLETE(
        "模型问题 · 未完成任务",
        "工具都调对了但没给出可用回答 —— 检查收尾提示与最大轮次设置。",
    ),
    TOOL_DEFECT(
        "工具问题",
        "工具返回了失败，或实现与 description 不符。**改工具，不是改提示词。**",
    ),
    CASE_PROBLEM(
        "用例问题",
        "用例本身不可判定（期望有歧义、时间基准错、多解被判唯一）。**修评测集。**",
    ),
    GRADER_PROBLEM(
        "评测问题",
        "匹配器过严：模型的做法其实合理，是断言写得比需求更死。**修匹配器。**",
    ),
}

data class CaseOutcome(
    val caseId: String,
    val category: String,
    val difficulty: String,
    val trajectory: Trajectory,
    val toolSelectionOk: Boolean,
    val forbiddenOk: Boolean,
    val argsOk: Boolean,
    val answerOk: Boolean,
    val failures: List<String>,
    val attribution: Attribution?,
    /** 这条用例是否声明了参数断言 —— 参数正确率的分母靠它算。 */
    val hasArgsExpectation: Boolean,
) {
    val passed: Boolean get() = failures.isEmpty()
}

object Evaluator {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun evaluate(
        case: EvalCase,
        category: String,
        trajectory: Trajectory,
        hasArgsExpectation: Boolean,
    ): CaseOutcome {
        val failures = mutableListOf<String>()

        // 1) 工具序列：严格有序、严格数量
        val actualTools = trajectory.toolCalls.map { it.name }
        val toolSelectionOk = actualTools == case.expect.tools
        if (!toolSelectionOk) {
            failures += "工具序列不符：期望 ${case.expect.tools}，实际 $actualTools"
        }

        // 2) 反向断言：出现了禁止的工具
        val hitForbidden = actualTools.filter { it in case.expect.forbidden }
        val forbiddenOk = hitForbidden.isEmpty()
        if (!forbiddenOk) {
            failures += "调用了禁止的工具：$hitForbidden"
        }

        // 3) 参数匹配器：对该工具的**任一次**调用满足即可
        var argsOk = true
        for ((toolName, matchers) in case.expect.args) {
            val invocations = trajectory.toolCalls.filter { it.name == toolName }
            if (invocations.isEmpty()) {
                argsOk = false
                failures += "没有 $toolName 的调用来校验参数"
                continue
            }
            for ((argName, matcher) in matchers) {
                val satisfied = invocations.any { inv ->
                    val value = argValue(inv.argumentsJson, argName)
                    matcher.matches(value)
                }
                if (!satisfied) {
                    argsOk = false
                    val seen = invocations.mapNotNull { argValue(it.argumentsJson, argName) }
                    failures += "$toolName.$argName 需要「${matcher.describe()}」，实际是 $seen"
                }
            }
        }

        // 4) 回答关键词
        var answerOk = true
        if (case.expect.answerContains.isNotEmpty()) {
            answerOk = case.expect.answerContains.any { trajectory.finalAnswer.contains(it) }
            if (!answerOk) {
                failures += "回答里没有出现任何期望关键词 ${case.expect.answerContains}：" +
                    trajectory.finalAnswer.take(120)
            }
        }

        // 5) 运行期异常
        if (trajectory.error != null) {
            failures += "运行时异常：${trajectory.error}"
        }

        // 6) 工具自己失败了 —— 工具名对不代表任务做成
        //    这条是自测抓出来的：原先只比对工具序列，于是「提醒时间已过去、
        //    ReminderTool 明确拒绝执行」的用例被误判成通过。
        for (call in trajectory.toolCalls.filter { !it.success }) {
            failures += "工具 ${call.name} 执行失败：${call.message}"
        }

        val attribution = if (failures.isEmpty()) {
            null
        } else {
            classify(case, trajectory, toolSelectionOk, forbiddenOk, argsOk, answerOk)
        }

        return CaseOutcome(
            caseId = case.id,
            category = category,
            difficulty = case.difficulty,
            trajectory = trajectory,
            toolSelectionOk = toolSelectionOk,
            forbiddenOk = forbiddenOk,
            argsOk = argsOk,
            answerOk = answerOk,
            failures = failures,
            attribution = attribution,
            hasArgsExpectation = hasArgsExpectation,
        )
    }

    /**
     * 归因分类。
     *
     * 顺序很重要：先判「工具自己是不是坏了」，再判模型的锅 —— 否则工具 bug 会被
     * 误算成模型不会用，进而去改提示词，越改越糟。
     */
    private fun classify(
        case: EvalCase,
        t: Trajectory,
        toolSelectionOk: Boolean,
        forbiddenOk: Boolean,
        argsOk: Boolean,
        answerOk: Boolean,
    ): Attribution = when {
        // 工具执行失败 —— 先查工具
        t.toolCalls.any { !it.success } -> Attribution.TOOL_DEFECT

        // 运行期异常且不是模型的问题
        t.error != null && t.toolCalls.isEmpty() -> Attribution.CASE_PROBLEM

        // 选了不该选的工具 / 该调没调
        !forbiddenOk || !toolSelectionOk -> Attribution.MODEL_TOOL_CHOICE

        // 工具选对了，参数不对
        !argsOk -> Attribution.MODEL_ARGUMENTS

        // 工具与参数都对，但回答不达标
        !answerOk -> Attribution.MODEL_INCOMPLETE

        else -> Attribution.CASE_PROBLEM
    }

    private fun argValue(argumentsJson: String, name: String): String? = runCatching {
        val obj = json.parseToJsonElement(argumentsJson) as? JsonObject ?: return null
        (obj[name] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
}

/** 把匹配器渲染成人能读的一句话，用在报告与失败文案里。 */
fun ArgMatcher.describe(): String = buildList {
    if (nonEmpty == true) add("非空")
    equalsAny?.let { add("等于 ${it.joinToString(" / ")}") }
    containsAny?.let { add("包含 ${it.joinToString(" / ")} 之一") }
    intRange?.let { if (it.size >= 2) add("取值在 ${it[0]}~${it[1]}") }
    if (iso8601 == true) add(if (at != null) "ISO 8601 且瞬时等于 $at" else "合法的 ISO 8601")
}.joinToString("，").ifEmpty { "（用例没声明任何断言）" }

/**
 * 匹配器求值：**所有被声明的断言都必须通过**，没声明的字段不参与判断。
 * 一个断言都没声明的匹配器会被判为不通过 —— 那说明用例写错了，不该静默放过。
 */
fun ArgMatcher.matches(value: String?): Boolean {
    if (value == null) return false

    var declared = false

    if (nonEmpty == true) {
        declared = true
        if (value.isBlank()) return false
    }
    equalsAny?.let { allowed ->
        declared = true
        if (value !in allowed) return false
    }
    containsAny?.let { needles ->
        declared = true
        if (needles.none { value.contains(it) }) return false
    }
    intRange?.let { range ->
        declared = true
        val n = value.toIntOrNull() ?: return false
        if (range.size >= 2 && (n < range[0] || n > range[1])) return false
    }
    if (iso8601 == true) {
        declared = true
        val instant = parseInstant(value) ?: return false
        at?.let { expected ->
            val expectedInstant = parseInstant(expected) ?: return false
            if (instant != expectedInstant) return false
        }
    }

    return declared
}

/** 接受带时区的 ISO 8601，也接受没带时区的（按系统时区解释，与 ReminderTool 一致）。 */
fun parseInstant(raw: String): Instant? =
    runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant()
        }.getOrNull()
