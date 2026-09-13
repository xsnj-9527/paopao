package com.deepseekbuddy.agent.llm

import com.deepseekbuddy.agent.AgentConfig
import com.deepseekbuddy.agent.AgentLogger
import com.deepseekbuddy.agent.ChatMessage
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 手写 SSE 客户端的测试。
 *
 * 这是全项目最该被测、却最容易漏测的一块：工具调用的参数是**分片到达**的，
 * 模型可能把 `{"title":"开会","triggerAt":"..."}` 拆成十几个 delta 逐步吐出来。
 *
 * 用 JDK 自带的 `com.sun.net.httpserver` 起假服务 —— **零新增依赖**，
 * 而且真的走一遍 HTTP，不是喂字符串给解析函数自欺欺人。
 */
class DeepSeekClientTest {

    /** 起一个假模型服务；chunks 逐段写出，可选段间延时来模拟 TCP 分包。 */
    private fun withFakeServer(
        chunks: List<String>,
        status: Int = 200,
        errorBody: String = "",
        onRequest: ((String) -> Unit)? = null,
        block: suspend (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange: HttpExchange ->
            onRequest?.invoke(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            if (status != 200) {
                val bytes = errorBody.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0) // 0 = chunked
            exchange.responseBody.use { out ->
                for (c in chunks) {
                    out.write(c.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                    Thread.sleep(2) // 让出，制造真实的分包
                }
            }
        }
        server.start()
        try {
            runBlocking { block("http://127.0.0.1:${server.address.port}/v1") }
        } finally {
            server.stop(0)
        }
    }

    private fun config(baseUrl: String) =
        AgentConfig(baseUrl = baseUrl, apiKey = "test-key", model = "deepseek-v4-flash")

    private fun sse(payload: String) = "data: $payload\n\n"

    private fun delta(json: String) = sse("""{"choices":[{"delta":$json}]}""")

    private val msgs = listOf(ChatMessage("user", "你好"))

    // ── 1. 纯文本流 ─────────────────────────────────────────────────────────

    @Test
    fun `文本增量被累积并逐片回调`() = withFakeServer(
        listOf(delta("""{"content":"你"}"""), delta("""{"content":"好"}"""), delta("""{"content":"呀"}"""), sse("[DONE]")),
    ) { baseUrl ->
        val seen = mutableListOf<String>()
        val resp = DeepSeekClient(config(baseUrl)).chat(msgs, emptyList(), { seen += it })

        assertEquals("你好呀", resp.text)
        assertEquals(listOf("你", "好", "呀"), seen)
        assertTrue(resp.toolCalls.isEmpty())
    }

    // ── 2. reasoning 独立通道 ───────────────────────────────────────────────

    @Test
    fun `reasoning 走独立通道不混入正文`() = withFakeServer(
        listOf(
            delta("""{"reasoning_content":"先想"}"""),
            delta("""{"reasoning_content":"一下"}"""),
            delta("""{"content":"答案"}"""),
            sse("[DONE]"),
        ),
    ) { baseUrl ->
        val reasonings = mutableListOf<String>()
        val resp = DeepSeekClient(config(baseUrl)).chat(msgs, emptyList(), {}, { reasonings += it })

        assertEquals("先想一下", resp.reasoningText)
        assertEquals("答案", resp.text)
        assertEquals(listOf("先想", "一下"), reasonings)
    }

    // ── 3. 工具调用参数分片归并（最关键）────────────────────────────────────

    @Test
    fun `工具调用的参数跨分片拼回`() = withFakeServer(
        listOf(
            delta("""{"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"create_","arguments":""}}]}"""),
            delta("""{"tool_calls":[{"index":0,"function":{"name":"reminder"}}]}"""),
            delta("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"title\""}}]}"""),
            delta("""{"tool_calls":[{"index":0,"function":{"arguments":":\"开会\","}}]}"""),
            delta("""{"tool_calls":[{"index":0,"function":{"arguments":"\"triggerAt\":\"2026-09-15T09:00:00+08:00\"}"}}]}"""),
            sse("[DONE]"),
        ),
    ) { baseUrl ->
        val resp = DeepSeekClient(config(baseUrl)).chat(msgs, emptyList(), {})

        assertEquals(1, resp.toolCalls.size)
        assertEquals("call_abc", resp.toolCalls[0].id)
        // name 分两片到达 —— 必须拼起来，覆盖式赋值会丢掉前缀
        assertEquals("create_reminder", resp.toolCalls[0].name)
        val args = resp.toolCalls[0].argumentsJson
        assertEquals("""{"title":"开会","triggerAt":"2026-09-15T09:00:00+08:00"}""", args)
        // 真正要保证的是「拼回来是合法 JSON」
        val title = Json.parseToJsonElement(args).jsonObject["title"].toString()
        assertEquals("\"开会\"", title)
    }

    @Test
    fun `多个工具调用按 index 排序而不是按到达顺序`() = withFakeServer(
        listOf(
            // 故意乱序到达：先 index 1，再 index 0
            delta("""{"tool_calls":[{"index":1,"id":"c1","function":{"name":"create_note","arguments":"{}"}}]}"""),
            delta("""{"tool_calls":[{"index":0,"id":"c0","function":{"name":"get_time","arguments":"{}"}}]}"""),
            sse("[DONE]"),
        ),
    ) { baseUrl ->
        val resp = DeepSeekClient(config(baseUrl)).chat(msgs, emptyList(), {})

        assertEquals(2, resp.toolCalls.size)
        // 执行顺序要和模型的意图一致，否则「先查时间再设提醒」会反过来
        assertEquals(listOf("get_time", "create_note"), resp.toolCalls.map { it.name })
        assertEquals(listOf("c0", "c1"), resp.toolCalls.map { it.id })
    }

    // ── 4. 容错 ─────────────────────────────────────────────────────────────

    @Test
    fun `非法行被跳过而不是整轮失败`() = withFakeServer(
        listOf(
            ": 这是注释行\n\n",
            "data: {这不是合法 JSON\n\n",
            "data: not-json-at-all\n\n",
            delta("""{"content":"仍然正常"}"""),
            sse("[DONE]"),
        ),
    ) { baseUrl ->
        val resp = DeepSeekClient(config(baseUrl)).chat(msgs, emptyList(), {})
        assertEquals("仍然正常", resp.text)
    }

    @Test
    fun `没有 DONE 直接断流时返回已收到的内容`() = withFakeServer(
        listOf(delta("""{"content":"半截"}""")),
    ) { baseUrl ->
        val resp = DeepSeekClient(config(baseUrl)).chat(msgs, emptyList(), {})
        assertEquals("半截", resp.text)
    }

    @Test
    fun `data 行被 TCP 切断也能还原`() {
        // 把一个完整帧切成三段，切点落在 JSON 中间
        val full = delta("""{"content":"被切开的文本"}""")
        val parts = listOf(full.substring(0, 20), full.substring(20, 40), full.substring(40))
        withFakeServer(parts + sse("[DONE]")) { baseUrl ->
            val resp = DeepSeekClient(config(baseUrl)).chat(msgs, emptyList(), {})
            assertEquals("被切开的文本", resp.text)
        }
    }

    // ── 5. HTTP 错误 ────────────────────────────────────────────────────────

    @Test
    fun `HTTP 错误抛出带状态码的异常`() = withFakeServer(
        emptyList(),
        status = 401,
        errorBody = """{"error":{"message":"invalid key"}}""",
    ) { baseUrl ->
        val e = assertFailsWith<DeepSeekApiException> {
            DeepSeekClient(config(baseUrl)).chat(msgs, emptyList(), {})
        }
        assertEquals(401, e.code)
        assertTrue(e.message!!.contains("invalid key"), e.message)
    }

    // ── 6. 请求体形状 ───────────────────────────────────────────────────────

    @Test
    fun `带历史与工具调用的请求体形状正确`() {
        var body = ""
        withFakeServer(listOf(sse("[DONE]")), onRequest = { body = it }) { baseUrl ->
            DeepSeekClient(config(baseUrl)).chat(
                listOf(
                    ChatMessage("system", "sys"),
                    ChatMessage("user", "q"),
                    ChatMessage("assistant", null, toolCalls = listOf(
                        com.deepseekbuddy.agent.ToolCall("c1", "get_time", "{}"),
                    )),
                    ChatMessage("tool", "成功：22:30", toolCallId = "c1"),
                ),
                listOf(Json.parseToJsonElement("""{"type":"function","function":{"name":"get_time"}}""").jsonObject),
                {},
            )
        }
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("true", obj["stream"].toString())
        assertEquals("\"deepseek-v4-flash\"", obj["model"].toString())
        assertTrue(body.contains("\"tool_calls\""), "assistant 的工具调用要序列化进请求体")
        assertTrue(body.contains("\"tool_call_id\":\"c1\""), "tool 消息要带 tool_call_id")
        assertTrue(body.contains("\"tools\""), "工具 schema 要发出去")
    }

    @Test
    fun `没有工具时不发 tools 字段`() {
        var body = ""
        withFakeServer(listOf(sse("[DONE]")), onRequest = { body = it }) { baseUrl ->
            DeepSeekClient(config(baseUrl)).chat(msgs, emptyList(), {})
        }
        assertTrue(!body.contains("\"tools\""), "没工具就别发，省 token：$body")
    }

    // ── 7. 日志出口 ─────────────────────────────────────────────────────────

    @Test
    fun `注入的 logger 能收到请求与完成日志`() = withFakeServer(
        listOf(delta("""{"content":"ok"}"""), sse("[DONE]")),
    ) { baseUrl ->
        val log = AgentLogger.Recording()
        DeepSeekClient(config(baseUrl), log).chat(msgs, emptyList(), {})

        assertTrue(log.messages().any { it.contains("POST") }, log.messages().toString())
        assertTrue(log.messages().any { it.contains("done:") }, log.messages().toString())
    }

    @Test
    fun `默认不注入 logger 时不产生任何副作用`() = withFakeServer(
        listOf(delta("""{"content":"ok"}"""), sse("[DONE]")),
    ) { baseUrl ->
        // 不抛错即为通过：默认出口是 AgentLogger.None
        val resp = DeepSeekClient(config(baseUrl)).chat(msgs, emptyList(), {})
        assertEquals("ok", resp.text)
    }
}
