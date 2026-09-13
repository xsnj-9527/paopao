package com.deepseekbuddy.agent

import com.deepseekbuddy.agent.tools.NoteTool
import com.deepseekbuddy.agent.tools.RememberFactTool
import com.deepseekbuddy.agent.tools.ReminderTool
import com.deepseekbuddy.agent.tools.TimeTool
import com.deepseekbuddy.agent.tools.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 四个内置工具的测试。
 *
 * 时钟是注入的 —— 所以「20 分钟后」这类相对时间断言是可回归的，
 * 不会因为跑测试的时刻不同而时好时坏。评测 runner 复用同一套注入方式。
 */
class ToolsTest {

    private val ctx = ToolContext(personaId = 7L)

    /** 冻结时间：2026-09-14 22:30:00 +08:00（星期一）。与 evals/cases 下的用例 JSON 里的 now 一致。 */
    private val frozenNow: ZonedDateTime =
        ZonedDateTime.of(2026, 9, 14, 22, 30, 0, 0, ZoneId.of("Asia/Shanghai"))

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // ── get_time ──────────────────────────────────────────────────────────

    @Test
    fun `get_time 用注入的时钟而不是系统时钟`() = runTest {
        val tool = TimeTool { frozenNow }

        val result = tool.execute(JsonObject(emptyMap()), ctx)

        assertTrue(result.success)
        assertEquals("当前时间是 2026年9月14日 星期一 22:30", result.message)
    }

    @Test
    fun `get_time 不需要任何参数`() {
        assertTrue(TimeTool().parameters.toString().contains("\"properties\""))
        assertTrue(TimeTool().parameters["required"].toString() == "[]", "required 应为空数组")
    }

    // ── create_note ───────────────────────────────────────────────────────

    @Test
    fun `create_note 正常写入`() = runTest {
        val store = InMemoryNoteStore()
        val tool = NoteTool(store)

        val result = tool.execute(args("""{"title":"购物清单","content":"牛奶、鸡蛋"}"""), ctx)

        assertTrue(result.success)
        assertEquals(1, store.notes.size)
        assertEquals("购物清单", store.notes[0].title)
        assertEquals("牛奶、鸡蛋", store.notes[0].content)
    }

    @Test
    fun `create_note 缺少 title 时失败且不写入`() = runTest {
        val store = InMemoryNoteStore()
        val tool = NoteTool(store)

        val result = tool.execute(args("""{"content":"只有正文"}"""), ctx)

        assertFalse(result.success)
        assertTrue(result.message.contains("title"), result.message)
        assertEquals(0, store.notes.size, "失败时绝不能落库")
    }

    @Test
    fun `create_note 空白 title 也算缺失`() = runTest {
        val store = InMemoryNoteStore()
        val result = NoteTool(store).execute(args("""{"title":"   ","content":"x"}"""), ctx)
        assertFalse(result.success)
        assertEquals(0, store.notes.size)
    }

    @Test
    fun `create_note 会去掉首尾空白`() = runTest {
        val store = InMemoryNoteStore()
        NoteTool(store).execute(args("""{"title":"  标题  ","content":"  正文  "}"""), ctx)
        assertEquals("标题", store.notes[0].title)
        assertEquals("正文", store.notes[0].content)
    }

    // ── remember_fact ─────────────────────────────────────────────────────

    @Test
    fun `remember_fact 缺省 category 与 importance`() = runTest {
        val store = InMemoryMemoryStore()
        val result = RememberFactTool(store).execute(args("""{"content":"我养了一只猫"}"""), ctx)

        assertTrue(result.success)
        assertEquals("事实", store.memories[0].category, "默认分类应为「事实」")
        assertEquals(3, store.memories[0].importance, "默认重要度应为 3")
        assertEquals(7L, store.memories[0].personaId)
    }

    @Test
    fun `remember_fact 尊重合法的 category 与 importance`() = runTest {
        val store = InMemoryMemoryStore()
        RememberFactTool(store).execute(
            args("""{"content":"不吃香菜","category":"偏好","importance":5}"""), ctx,
        )
        assertEquals("偏好", store.memories[0].category)
        assertEquals(5, store.memories[0].importance)
    }

    @Test
    fun `remember_fact 非法 category 退化为事实`() = runTest {
        val store = InMemoryMemoryStore()
        RememberFactTool(store).execute(args("""{"content":"x","category":"随便写的"}"""), ctx)
        assertEquals("事实", store.memories[0].category)
    }

    @Test
    fun `remember_fact importance 越界被夹到 1-5`() = runTest {
        val store = InMemoryMemoryStore()
        RememberFactTool(store).execute(args("""{"content":"a","importance":99}"""), ctx)
        RememberFactTool(store).execute(args("""{"content":"b","importance":-3}"""), ctx)
        RememberFactTool(store).execute(args("""{"content":"c","importance":"不是数字"}"""), ctx)
        assertEquals(5, store.memories[0].importance)
        assertEquals(1, store.memories[1].importance)
        assertEquals(3, store.memories[2].importance, "解析不出来时回落到默认 3")
    }

    @Test
    fun `remember_fact 在群聊 personaId 为 0 时拒绝写入`() = runTest {
        val store = InMemoryMemoryStore()
        val result = RememberFactTool(store).execute(
            args("""{"content":"秘密"}"""), ToolContext(personaId = 0L),
        )
        assertFalse(result.success)
        assertEquals(0, store.memories.size, "群聊场景不得写长期记忆")
    }

    // ── create_reminder ───────────────────────────────────────────────────

    @Test
    fun `create_reminder 接受带时区的 ISO 8601`() = runTest {
        val gw = RecordingReminderGateway()
        val result = ReminderTool(gw, now = { frozenNow.toInstant().toEpochMilli() })
            .execute(args("""{"title":"开会","triggerAt":"2026-09-15T09:00:00+08:00"}"""), ctx)

        assertTrue(result.success, result.message)
        assertEquals(1, gw.scheduled.size)
        assertEquals("开会", gw.scheduled[0].title)
        // 2026-09-15 09:00 +08:00 的 UTC 瞬时
        assertEquals(ZonedDateTime.of(2026, 9, 15, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
            .toInstant().toEpochMilli(), gw.scheduled[0].triggerAtMillis)
    }

    @Test
    fun `create_reminder 接受 UTC 的 Z 结尾写法`() = runTest {
        val gw = RecordingReminderGateway()
        val result = ReminderTool(gw, now = { frozenNow.toInstant().toEpochMilli() })
            .execute(args("""{"title":"x","triggerAt":"2026-09-15T01:00:00Z"}"""), ctx)
        assertTrue(result.success, result.message)
        assertEquals(1, gw.scheduled.size)
    }

    @Test
    fun `create_reminder 缺时区时按系统时区解释`() = runTest {
        val gw = RecordingReminderGateway()
        val result = ReminderTool(gw, now = { frozenNow.toInstant().toEpochMilli() })
            .execute(args("""{"title":"x","triggerAt":"2026-09-15T09:00:00"}"""), ctx)
        assertTrue(result.success, result.message)
        assertEquals(
            ZonedDateTime.of(2026, 9, 15, 9, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli(),
            gw.scheduled[0].triggerAtMillis,
        )
    }

    @Test
    fun `create_reminder 拒绝已过去的时间`() = runTest {
        val gw = RecordingReminderGateway()
        val result = ReminderTool(gw, now = { frozenNow.toInstant().toEpochMilli() })
            .execute(args("""{"title":"昨天的会","triggerAt":"2026-09-14T20:00:00+08:00"}"""), ctx)

        assertFalse(result.success)
        assertTrue(result.message.contains("必须晚于当前时间"), result.message)
        assertEquals(0, gw.scheduled.size, "过去的时间绝不能进调度器")
    }

    @Test
    fun `create_reminder 拒绝无法解析的时间并给出格式指引`() = runTest {
        val gw = RecordingReminderGateway()
        val result = ReminderTool(gw, now = { frozenNow.toInstant().toEpochMilli() })
            .execute(args("""{"title":"x","triggerAt":"明天早上七点半"}"""), ctx)

        assertFalse(result.success)
        assertTrue(result.message.contains("ISO 8601"), "要告诉模型正确格式：${result.message}")
        assertEquals(0, gw.scheduled.size)
    }

    @Test
    fun `create_reminder 缺 title 或 triggerAt 都失败`() = runTest {
        val gw = RecordingReminderGateway()
        val tool = ReminderTool(gw, now = { frozenNow.toInstant().toEpochMilli() })
        assertFalse(tool.execute(args("""{"triggerAt":"2026-09-15T09:00:00+08:00"}"""), ctx).success)
        assertFalse(tool.execute(args("""{"title":"x"}"""), ctx).success)
        assertEquals(0, gw.scheduled.size)
    }

    @Test
    fun `create_reminder 成功时的回执里带可读时间`() = runTest {
        val gw = RecordingReminderGateway()
        val result = ReminderTool(gw, now = { frozenNow.toInstant().toEpochMilli() })
            .execute(args("""{"title":"开会","triggerAt":"2026-09-15T09:00:00+08:00"}"""), ctx)
        assertTrue(result.message.contains("已设置提醒"), result.message)
        assertTrue(result.message.contains("9月15日 09:00"), "回执要给模型可读的时间：${result.message}")
    }

    // ── 工具契约本身 ──────────────────────────────────────────────────────

    @Test
    fun `四个工具的名称与必填参数符合评测用例的假设`() {
        assertEquals("get_time", TimeTool().name)
        assertEquals("create_note", NoteTool(InMemoryNoteStore()).name)
        assertEquals("remember_fact", RememberFactTool(InMemoryMemoryStore()).name)
        assertEquals("create_reminder", ReminderTool(RecordingReminderGateway()).name)

        fun required(tool: com.deepseekbuddy.agent.tools.Tool): List<String> =
            (tool.parameters["required"] as? kotlinx.serialization.json.JsonArray)
                ?.map { it.toString().trim('"') } ?: emptyList()

        assertEquals(emptyList(), required(TimeTool()))
        assertEquals(listOf("title", "content"), required(NoteTool(InMemoryNoteStore())))
        assertEquals(listOf("content"), required(RememberFactTool(InMemoryMemoryStore())))
        assertEquals(listOf("title", "triggerAt"), required(ReminderTool(RecordingReminderGateway())))
    }

    @Test
    fun `所有内置工具的 schema 都是合法的 OpenAI function 格式`() {
        val tools = listOf(
            TimeTool(), NoteTool(InMemoryNoteStore()),
            RememberFactTool(InMemoryMemoryStore()), ReminderTool(RecordingReminderGateway()),
        )
        for (tool in tools) {
            assertEquals("object", tool.parameters["type"].toString().trim('"'), tool.name)
            assertTrue(tool.parameters["properties"] != null, "${tool.name} 缺 properties")
            assertTrue(tool.description.isNotBlank(), "${tool.name} 缺 description")
        }
    }
}
