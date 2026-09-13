package com.deepseekbuddy.agent

import com.deepseekbuddy.agent.llm.ChatResponse
import com.deepseekbuddy.agent.tools.RiskLevel
import com.deepseekbuddy.agent.tools.TimeTool
import com.deepseekbuddy.agent.tools.ToolRegistry
import com.deepseekbuddy.agent.tools.ToolResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Agent 循环的行为测试。
 *
 * 全部跑在纯 JVM 上：没有 Room、没有 AlarmManager、没有模拟器、不联网。
 * 这就是抽 `:agent-core` 换来的东西。
 */
class AgentEngineTest {

    private val config = AgentConfig(apiKey = "test-key-not-used")

    private fun engineWith(
        registry: ToolRegistry,
        client: FakeLlmClient,
    ) = AgentEngine(registry, { client })

    private suspend fun runOnce(
        engine: AgentEngine,
        recorder: TrajectoryRecorder,
        userText: String = "你好",
        confirm: suspend (String, String) -> Boolean = { _, _ -> true },
        personaId: Long = 1L,
    ): String = engine.run(
        config = config,
        history = emptyList(),
        userText = userText,
        systemPrompt = "你是测试助手",
        personaId = personaId,
        onDelta = recorder::onDelta,
        onToolCall = recorder::onToolCall,
        onToolResult = recorder::onToolResult,
        onConfirmRequest = { name, args -> confirm(name, args).also { recorder.onConfirm(name, it) } },
    )

    @Test
    fun `不调用工具时直接返回文本`() = runTest {
        val client = FakeLlmClient.textOnly("你好呀")
        val engine = engineWith(ToolRegistry(emptyList()), client)
        val rec = TrajectoryRecorder()

        val answer = runOnce(engine, rec)

        assertEquals("你好呀", answer)
        assertEquals(1, client.callCount, "只该请求一次模型")
        assertTrue(rec.toolNames().isEmpty(), "不该有任何工具调用")
        assertEquals(listOf("你好呀"), rec.events.filterIsInstance<TrajectoryRecorder.Event.Delta>().map { it.text })
    }

    @Test
    fun `一次工具调用会执行并把结果回填给模型`() = runTest {
        val tool = ProbeTool("get_time", result = ToolResult.ok("当前时间是 2026年9月14日"))
        val client = FakeLlmClient.toolThenText("get_time", "{}", "现在是 9 月 14 日")
        val engine = engineWith(ToolRegistry(listOf(tool)), client)
        val rec = TrajectoryRecorder()

        val answer = runOnce(engine, rec, userText = "现在几点")

        assertEquals("现在是 9 月 14 日", answer)
        assertEquals(1, tool.executions, "工具必须被真实执行一次")
        assertEquals(2, client.callCount, "工具回填之后要再请求一次模型")
        assertEquals(listOf("get_time"), rec.toolNames())

        // 第二次请求里必须带上 assistant 的 tool_calls 与 role=tool 的结果
        val secondMessages = client.calls[1].messages
        assertTrue(
            secondMessages.any { it.role == "tool" && it.content?.contains("2026年9月14日") == true },
            "工具结果必须以 role=tool 回填，实际：${secondMessages.map { it.role }}",
        )
        assertTrue(
            secondMessages.any { it.role == "assistant" && it.toolCalls?.firstOrNull()?.name == "get_time" },
            "必须回填 assistant 的 tool_calls",
        )
    }

    @Test
    fun `工具 schema 随第一次请求发给模型`() = runTest {
        val client = FakeLlmClient.textOnly("好的")
        val engine = engineWith(ToolRegistry(listOf(TimeTool())), client)
        runOnce(engine, TrajectoryRecorder())

        val sent = client.calls[0].tools
        assertEquals(1, sent.size, "注册了几个工具就该发几个")
        assertTrue(sent[0].toString().contains("get_time"), "schema 里要有工具名：${sent[0]}")
        assertTrue(sent[0].toString().contains("function"), "必须是 OpenAI function 格式")
    }

    @Test
    fun `FORBIDDEN 工具被直接拦截不执行`() = runTest {
        val tool = ProbeTool("danger", riskLevel = RiskLevel.FORBIDDEN)
        val client = FakeLlmClient.toolThenText("danger", "{}", "好的")
        val engine = engineWith(ToolRegistry(listOf(tool)), client)
        val rec = TrajectoryRecorder()

        runOnce(engine, rec)

        assertEquals(0, tool.executions, "被禁用的工具绝不能执行")
        val results = rec.toolResults()
        assertEquals(1, results.size)
        assertEquals(false, results[0].success)
        assertTrue(results[0].message.contains("禁用"), "提示应说明原因：${results[0].message}")
    }

    @Test
    fun `CONFIRM 工具在用户同意后执行`() = runTest {
        val tool = ProbeTool("send_msg", riskLevel = RiskLevel.CONFIRM)
        val client = FakeLlmClient.toolThenText("send_msg", """{"to":"妈妈"}""", "已发送")
        val engine = engineWith(ToolRegistry(listOf(tool)), client)
        val rec = TrajectoryRecorder()

        runOnce(engine, rec, confirm = { _, _ -> true })

        assertEquals(1, tool.executions)
        assertEquals(true, rec.toolResults()[0].success)
    }

    @Test
    fun `CONFIRM 工具在用户拒绝后不执行且回填拒绝结果`() = runTest {
        val tool = ProbeTool("send_msg", riskLevel = RiskLevel.CONFIRM)
        val client = FakeLlmClient.toolThenText("send_msg", """{"to":"妈妈"}""", "那就不发了")
        val engine = engineWith(ToolRegistry(listOf(tool)), client)
        val rec = TrajectoryRecorder()

        runOnce(engine, rec, confirm = { _, _ -> false })

        assertEquals(0, tool.executions, "用户拒绝后绝不能执行")
        val r = rec.toolResults()[0]
        assertEquals(false, r.success)
        assertTrue(r.message.contains("拒绝"), "回填要给模型明确的拒绝信号：${r.message}")

        // 拒绝也要如实告诉模型（role=tool），否则模型会以为发成功了
        val second = client.calls[1].messages
        assertTrue(
            second.any { it.role == "tool" && it.content?.contains("拒绝") == true },
            "拒绝结果必须回填：${second.map { it.role to it.content }}",
        )
    }

    @Test
    fun `确认回调能拿到工具名与原始参数`() = runTest {
        val tool = ProbeTool("send_msg", riskLevel = RiskLevel.CONFIRM)
        val client = FakeLlmClient.toolThenText("send_msg", """{"to":"妈妈","text":"晚安"}""", "ok")
        val engine = engineWith(ToolRegistry(listOf(tool)), client)

        var seenName = ""
        var seenArgs = ""
        runOnce(engine, TrajectoryRecorder(), confirm = { n, a -> seenName = n; seenArgs = a; true })

        assertEquals("send_msg", seenName)
        assertTrue(seenArgs.contains("妈妈"), "参数要原样传给确认层：$seenArgs")
    }

    @Test
    fun `达到最大轮次后优雅收尾而不是死循环`() = runTest {
        // 模型每轮都要求调工具，永不收敛
        val tool = ProbeTool("loop_tool")
        val endless = FakeLlmClient(
            List(20) { ChatResponse("", "", listOf(ToolCall("c$it", "loop_tool", "{}"))) },
        )
        val engine = engineWith(ToolRegistry(listOf(tool)), endless)
        val rec = TrajectoryRecorder()

        val answer = runOnce(engine, rec)

        assertTrue(answer.contains("绕了太多圈"), "超限后要有明确收尾语：$answer")
        assertEquals(AgentEngine.MAX_TOOL_ITERATIONS + 1, endless.callCount,
            "请求次数应为 MAX_TOOL_ITERATIONS + 1")
        assertEquals(AgentEngine.MAX_TOOL_ITERATIONS + 1, tool.executions)
    }

    @Test
    fun `未知工具回填失败而不是崩溃`() = runTest {
        val client = FakeLlmClient.toolThenText("不存在的工具", "{}", "好的")
        val engine = engineWith(ToolRegistry(emptyList()), client)
        val rec = TrajectoryRecorder()

        runOnce(engine, rec)

        val r = rec.toolResults()[0]
        assertEquals(false, r.success)
        assertTrue(r.message.contains("未知工具"), r.message)
    }

    @Test
    fun `工具内部抛异常被兜住并回填失败`() = runTest {
        val tool = ProbeTool("boom", throwOnExecute = true)
        val client = FakeLlmClient.toolThenText("boom", "{}", "好的")
        val engine = engineWith(ToolRegistry(listOf(tool)), client)
        val rec = TrajectoryRecorder()

        runOnce(engine, rec)

        val r = rec.toolResults()[0]
        assertEquals(false, r.success)
        assertTrue(r.message.contains("工具执行异常"), r.message)
    }

    @Test
    fun `工具参数是非法 JSON 时回填空参数而不是崩溃`() = runTest {
        val tool = ProbeTool("t")
        val client = FakeLlmClient.toolThenText("t", "{这不是 JSON", "好的")
        val engine = engineWith(ToolRegistry(listOf(tool)), client)

        runOnce(engine, TrajectoryRecorder())

        assertEquals(1, tool.executions, "非法参数应退化为空对象后照常执行")
    }

    @Test
    fun `历史消息被拼在 system 与当前 user 之间`() = runTest {
        val client = FakeLlmClient.textOnly("好的")
        val engine = engineWith(ToolRegistry(emptyList()), client)

        engine.run(
            config = config,
            history = listOf(
                ChatMessage("user", "我养了只猫"),
                ChatMessage("assistant", "记下了"),
            ),
            userText = "它叫什么来着",
            systemPrompt = "你是助手",
            personaId = 1L,
            onDelta = {},
            onToolCall = { _, _ -> },
            onToolResult = { _ -> },
            onConfirmRequest = { _, _ -> true },
        )

        val roles = client.calls[0].messages.map { it.role }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
        assertEquals("它叫什么来着", client.calls[0].messages.last().content)
    }

    @Test
    fun `personaId 被透传给工具上下文`() = runTest {
        var seenPersona = -1L
        val tool = object : com.deepseekbuddy.agent.tools.Tool {
            override val name = "spy"
            override val description = "spy"
            override val parameters = kotlinx.serialization.json.buildJsonObject { }
            override val riskLevel = RiskLevel.NONE
            override suspend fun execute(
                args: kotlinx.serialization.json.JsonObject,
                ctx: com.deepseekbuddy.agent.tools.ToolContext,
            ): ToolResult { seenPersona = ctx.personaId; return ToolResult.ok("ok") }
        }
        val client = FakeLlmClient.toolThenText("spy", "{}", "done")
        val engine = engineWith(ToolRegistry(listOf(tool)), client)

        runOnce(engine, TrajectoryRecorder(), personaId = 42L)

        assertEquals(42L, seenPersona, "记忆按角色隔离，personaId 必须传到工具层")
    }

    @Test
    fun `reasoning 回调被独立转发`() = runTest {
        val client = FakeLlmClient(listOf(ChatResponse("正文", "这是思考过程", emptyList())))
        val engine = engineWith(ToolRegistry(emptyList()), client)

        val reasonings = mutableListOf<String>()
        engine.run(
            config = config, history = emptyList(), userText = "hi", systemPrompt = "s", personaId = 1L,
            onDelta = {}, onReasoning = { reasonings += it },
            onToolCall = { _, _ -> }, onToolResult = { _ -> }, onConfirmRequest = { _, _ -> true },
        )

        assertEquals(listOf("这是思考过程"), reasonings)
    }
}
