package com.deepseekbuddy.agent.eval

import com.deepseekbuddy.agent.AgentConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 评测器自己的测试。
 *
 * 「谁来评测评测器？」—— 如果匹配器和归因分类本身是错的，那么跑出来的漂亮指标
 * 毫无意义。这里用手写的模型脚本构造出 6 种已知情形（1 通过 + 5 种失败），
 * 然后钉死指标数值与归因分类：
 *
 *   A 通过       → 不进归因表
 *   B 该调没调   → 模型问题 · 工具选择
 *   C 调了禁止的 → 模型问题 · 工具选择
 *   D 工具对参数错 → 模型问题 · 参数编造
 *   E 工具自己失败 → 工具问题
 *   F 工具对但没完成 → 模型问题 · 未完成任务
 */
class EvaluatorSelfTest {

    private val now = "2026-09-14T22:30:00+08:00"
    private val config = AgentConfig(apiKey = "selftest")

    private fun case(
        id: String,
        prompt: String,
        tools: List<String> = emptyList(),
        forbidden: List<String> = emptyList(),
        args: Map<String, Map<String, ArgMatcher>> = emptyMap(),
        answerContains: List<String> = emptyList(),
    ) = EvalCase(
        id = id,
        prompt = prompt,
        expect = Expect(tools, forbidden, args, answerContains),
    )

    /** 脚本：一轮是模型回复（可能带工具调用），按顺序给。 */
    private fun script(vararg turns: RecordedTurn) = turns.toList()

    private fun call(id: String, name: String, argsJson: String) =
        RecordedToolCall(id, name, argsJson)

    private fun textTurn(t: String) = RecordedTurn(text = t)

    private val fixture: Pair<List<Pair<EvalCase, String>>, Map<String, List<RecordedTurn>>> =
        listOf(
            // A：正确调用 get_time
            case("A-pass", "现在几点了？", tools = listOf("get_time")) to "time",
            // B：该调工具却直接编了个答案
            case("B-no-tool", "现在几点了？", tools = listOf("get_time")) to "time",
            // C：纯计算题，却去查了时间
            case("C-forbidden", "1+1 等于几？", tools = emptyList(), forbidden = listOf("get_time")) to "time",
            // D：设提醒时间算错（期望 09-15 07:30，脚本给 09:00）
            case(
                "D-wrong-arg", "明天早上七点半叫我起床。",
                tools = listOf("create_reminder"),
                args = mapOf(
                    "create_reminder" to mapOf(
                        "triggerAt" to ArgMatcher(iso8601 = true, at = "2026-09-15T07:30:00+08:00"),
                    ),
                ),
            ) to "reminder",
            // E：让工具自己失败（时间已过去，ReminderTool 会拒绝）
            case("E-tool-defect", "提醒我看球。", tools = listOf("create_reminder")) to "reminder",
            // F：工具调对了，但回答里缺关键词
            case(
                "F-incomplete", "记住我对芒果过敏。",
                tools = listOf("remember_fact"),
                answerContains = listOf("芒果"),
            ) to "memory",
        ) to mapOf(
            "A-pass" to script(
                RecordedTurn(toolCalls = listOf(call("c1", "get_time", "{}"))),
                textTurn("现在是 22:30"),
            ),
            "B-no-tool" to script(textTurn("下午三点左右吧")),
            "C-forbidden" to script(
                RecordedTurn(toolCalls = listOf(call("c1", "get_time", "{}"))),
                textTurn("2"),
            ),
            "D-wrong-arg" to script(
                RecordedTurn(toolCalls = listOf(call("c1", "create_reminder", """{"title":"起床","triggerAt":"2026-09-15T09:00:00+08:00"}"""))),
                textTurn("设好了"),
            ),
            "E-tool-defect" to script(
                RecordedTurn(toolCalls = listOf(call("c1", "create_reminder", """{"title":"看球","triggerAt":"2026-09-14T20:00:00+08:00"}"""))),
                textTurn("设好了"),
            ),
            "F-incomplete" to script(
                RecordedTurn(toolCalls = listOf(call("c1", "remember_fact", """{"content":"对芒果过敏"}"""))),
                textTurn("好的"),
            ),
        )

    private suspend fun runFixture(): List<CaseOutcome> {
        val (cases, scripts) = fixture
        val source = LlmSources.of(EvalMode.Replay(ReplayFile(cases = scripts), null))
        return cases.map { (c, category) ->
            val t = EvalHarness.runCase(c, category, now, source, config)
            Evaluator.evaluate(c, category, t, c.expect.args.isNotEmpty())
        }
    }

    @Test
    fun `六种已知情形被判成预期的对错`() = runTest {
        val outcomes = runFixture().associateBy { it.caseId }

        assertTrue(outcomes.getValue("A-pass").passed, "A 应当通过：${outcomes.getValue("A-pass").failures}")
        assertTrue(!outcomes.getValue("B-no-tool").passed, "B 应当失败（没调工具）")
        assertTrue(!outcomes.getValue("C-forbidden").passed, "C 应当失败（调了禁止工具）")
        assertTrue(!outcomes.getValue("D-wrong-arg").passed, "D 应当失败（时间算错）")
        assertTrue(!outcomes.getValue("E-tool-defect").passed, "E 应当失败（工具拒绝执行）")
        assertTrue(!outcomes.getValue("F-incomplete").passed, "F 应当失败（回答缺关键词）")
    }

    @Test
    fun `归因分类落在正确的格子里`() = runTest {
        val o = runFixture().associateBy { it.caseId }

        assertEquals(null, o.getValue("A-pass").attribution, "通过的用例不该有归因")
        assertEquals(Attribution.MODEL_TOOL_CHOICE, o.getValue("B-no-tool").attribution)
        assertEquals(Attribution.MODEL_TOOL_CHOICE, o.getValue("C-forbidden").attribution)
        assertEquals(Attribution.MODEL_ARGUMENTS, o.getValue("D-wrong-arg").attribution)
        // 关键：工具返回失败时先判工具，不能算成模型的锅
        assertEquals(Attribution.TOOL_DEFECT, o.getValue("E-tool-defect").attribution)
        assertEquals(Attribution.MODEL_INCOMPLETE, o.getValue("F-incomplete").attribution)
    }

    @Test
    fun `工具拒绝执行时结果里如实记下失败原因`() = runTest {
        val e = runFixture().associateBy { it.caseId }.getValue("E-tool-defect")
        val callRecord = e.trajectory.toolCalls.single()
        assertEquals(false, callRecord.success)
        assertTrue(callRecord.message.contains("必须晚于当前时间"), callRecord.message)
    }

    @Test
    fun `冻结时间在整条链路上生效`() = runTest {
        // D 用例的时间基准若没被冻结，2026-09-15T07:30 这个断言就没有意义了
        val d = runFixture().associateBy { it.caseId }.getValue("D-wrong-arg")
        assertTrue(d.failures.any { it.contains("triggerAt") }, "应当报出 triggerAt 不满足：${d.failures}")

        // get_time 返回的必须是冻结时间
        val t = EvalHarness.runCase(
            case("T", "现在几点了？", tools = listOf("get_time")), "time", now,
            LlmSources.of(EvalMode.Replay(ReplayFile(cases = mapOf("T" to script(
                RecordedTurn(toolCalls = listOf(call("c1", "get_time", "{}"))),
                textTurn("ok"),
            ))), null)),
            config,
        )
        assertTrue(
            t.toolCalls.single().message.contains("2026年9月14日 星期一 22:30"),
            "时钟没被冻结：${t.toolCalls.single().message}",
        )
    }

    @Test
    fun `指标由各维度判定直接算出，数值可复现`() = runTest {
        val outcomes = runFixture()
        val results = outcomes.map {
            CaseResult(
                id = it.caseId, category = it.category, difficulty = it.difficulty,
                passed = it.passed, failures = it.failures,
                attribution = it.attribution?.name, attributionLabel = it.attribution?.label,
                tools = it.trajectory.toolCalls.map { c -> c.name },
                rounds = it.trajectory.rounds, estimatedTokens = it.trajectory.estimatedTokens,
                toolSelectionOk = it.toolSelectionOk, forbiddenOk = it.forbiddenOk,
                argsOk = it.argsOk, hasArgsExpectation = it.hasArgsExpectation,
            )
        }
        val report = buildReport(results, EvalMode.NullModel, "selftest")

        assertEquals(6, report.total)
        assertEquals(1, report.passed)
        assertEquals(1.0 / 6, report.metrics.taskCompletionRate, 1e-9)
        // A/D/E/F 四条工具选择是对的，B/C 不是
        assertEquals(4.0 / 6, report.metrics.toolSelectionAccuracy, 1e-9)
        // 只有 D 声明了参数断言，且它失败了
        assertEquals(0.0, report.metrics.argumentAccuracy, 1e-9)
        // 轮次：A=2 B=1 C=2 D=2 E=2 F=2
        assertEquals(11.0 / 6, report.metrics.avgRounds, 1e-9)

        assertEquals(
            mapOf(
                "MODEL_ARGUMENTS" to 1,
                "MODEL_INCOMPLETE" to 1,
                "MODEL_TOOL_CHOICE" to 2,
                "TOOL_DEFECT" to 1,
            ),
            report.attributionSummary,
        )
    }

    @Test
    fun `匹配器逐条语义正确`() {
        // nonEmpty
        assertTrue(ArgMatcher(nonEmpty = true).matches("x"))
        assertTrue(!ArgMatcher(nonEmpty = true).matches("   "))
        assertTrue(!ArgMatcher(nonEmpty = true).matches(null))

        // containsAny
        assertTrue(ArgMatcher(containsAny = listOf("猫", "狗")).matches("我养了一只猫"))
        assertTrue(!ArgMatcher(containsAny = listOf("猫", "狗")).matches("我养了一只鸟"))

        // equalsAny
        assertTrue(ArgMatcher(equalsAny = listOf("偏好")).matches("偏好"))
        assertTrue(!ArgMatcher(equalsAny = listOf("偏好")).matches("事实"))

        // intRange
        assertTrue(ArgMatcher(intRange = listOf(4, 5)).matches("5"))
        assertTrue(!ArgMatcher(intRange = listOf(4, 5)).matches("3"))
        assertTrue(!ArgMatcher(intRange = listOf(4, 5)).matches("不是数字"))

        // iso8601 瞬时点比较：不同写法但同一时刻应当算相等
        assertTrue(
            ArgMatcher(iso8601 = true, at = "2026-09-15T07:30:00+08:00")
                .matches("2026-09-14T23:30:00Z"),
            "同一瞬时的不同时区写法必须判等",
        )
        assertTrue(
            !ArgMatcher(iso8601 = true, at = "2026-09-15T07:30:00+08:00")
                .matches("2026-09-15T09:00:00+08:00"),
        )
        assertTrue(!ArgMatcher(iso8601 = true).matches("明天早上七点半"))

        // 一个断言都没声明 —— 视为用例写错，不通过
        assertTrue(!ArgMatcher().matches("随便什么"), "空匹配器不该静默通过")
    }

    @Test
    fun `多操作组合的用例能同时校验多个参数`() {
        val m = ArgMatcher(containsAny = listOf("番茄"))
        assertTrue(m.matches("今天做了番茄炒蛋"))
        assertTrue(!m.matches("今天做了蛋炒饭"))
    }
}
