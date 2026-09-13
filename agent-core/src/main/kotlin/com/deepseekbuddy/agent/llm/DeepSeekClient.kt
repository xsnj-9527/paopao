package com.deepseekbuddy.agent.llm

import com.deepseekbuddy.agent.AgentConfig
import com.deepseekbuddy.agent.AgentLogger
import com.deepseekbuddy.agent.ChatMessage
import com.deepseekbuddy.agent.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class DeepSeekApiException(val code: Int, detail: String) :
    Exception("DeepSeek API 错误（$code）：$detail")

class DeepSeekNetworkException(cause: Throwable) :
    Exception("网络请求失败：${cause.message}", cause)

/**
 * DeepSeek 流式客户端（OpenAI 兼容接口）。
 * SSE 逐行解析；协程取消时主动 cancel OkHttp call。
 */
class DeepSeekClient(
    private val config: AgentConfig,
    /** 日志出口。默认吞掉。 */
    private val log: AgentLogger = AgentLogger.None,
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<JsonObject>,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
    ): ChatResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", config.temperature)
            putJsonObject("thinking") {
                put("type", if (config.thinking) "enabled" else "disabled")
            }
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role)
                        put("content", m.content ?: "")
                        m.toolCallId?.let { put("tool_call_id", it) }
                        m.toolCalls?.let { calls ->
                            putJsonArray("tool_calls") {
                                calls.forEach { tc ->
                                    addJsonObject {
                                        put("id", tc.id)
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", tc.name)
                                            put("arguments", tc.argumentsJson)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 没有工具时**不发** tools 字段：空数组白占 token，
            // 个别网关还会对空 tools 报错。
            if (tools.isNotEmpty()) {
                putJsonArray("tools") { tools.forEach { add(it) } }
            }
        }

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(request)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            log.d(TAG, "POST ${config.baseUrl}/chat/completions | model=${config.model} | messages=${messages.size} | tools=${tools.size}")
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    log.w(TAG, "HTTP ${resp.code}")
                    throw parseError(resp)
                }
                val source = resp.body?.source() ?: return@use ChatResponse("", "", emptyList())
                val text = StringBuilder()
                val reasoning = StringBuilder()
                val pending = mutableMapOf<Int, PendingToolCall>()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val chunk = runCatching { json.decodeFromString<ChatChunk>(data) }.getOrNull() ?: continue
                    val choice = chunk.choices.firstOrNull() ?: continue
                    choice.delta.content?.takeIf { it.isNotEmpty() }?.let { d ->
                        text.append(d)
                        onDelta(d)
                    }
                    choice.delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let { r ->
                        reasoning.append(r)
                        onReasoning(r)
                    }
                    choice.delta.toolCalls?.forEach { tc ->
                        val acc = pending.getOrPut(tc.index) { PendingToolCall() }
                        // id 只认第一个非空的
                        if (acc.id == null && !tc.id.isNullOrEmpty()) acc.id = tc.id
                        // name 必须**拼接**：模型可能把它拆成多片送出，
                        // 覆盖式赋值会丢掉前缀，导致工具名对不上（"create_" + "reminder"）。
                        tc.function?.name?.let { acc.name = (acc.name ?: "") + it }
                        tc.function?.arguments?.let { acc.arguments.append(it) }
                    }
                }
                val result = ChatResponse(
                    text.toString(),
                    reasoning.toString(),
                    // 按 index 排序：执行顺序要与模型的意图一致，
                    // 否则「先查时间再设提醒」可能反过来执行。
                    pending.entries.sortedBy { it.key }.mapNotNull { (index, v) ->
                        val name = v.name ?: ""
                        // 没有 name 的碎片是无法执行的半截调用，丢掉
                        if (name.isEmpty()) return@mapNotNull null
                        ToolCall(
                            id = v.id ?: "call_$index",
                            name = name,
                            // 空参数补成 {}，省得下游再兜一次解析失败
                            argumentsJson = if (v.arguments.isEmpty()) "{}" else v.arguments.toString(),
                        )
                    },
                )
                log.d(TAG, "done: textLen=${result.text.length} reasoningLen=${result.reasoningText.length} toolCalls=${result.toolCalls.size}")
                result
            }
        } catch (e: IOException) {
            if (e.message?.contains("Canceled") == true) throw CancellationException("用户停止")
            log.e(TAG, "io error: ${e.message}")
            throw DeepSeekNetworkException(e)
        } finally {
            cancelHandle?.dispose()
        }
    }

    private fun parseError(resp: okhttp3.Response): DeepSeekApiException {
        val raw = resp.body?.string() ?: ""
        val detail = runCatching {
            json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull() ?: raw.take(200)
        log.e(TAG, "api error body: $raw".take(500))
        return DeepSeekApiException(resp.code, detail)
    }

    companion object {
        private const val TAG = "DeepSeekClient"
    }

    private class PendingToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}

@Serializable
private data class ChatChunk(
    val choices: List<Choice> = emptyList(),
)

@Serializable
private data class Choice(
    val delta: Delta = Delta(),
)

@Serializable
private data class Delta(
    val content: String? = null,
    // JSON 字段是 tool_calls（下划线），必须显式映射，否则工具调用永远解析不出来
    @SerialName("tool_calls")
    val toolCalls: List<DeltaToolCall>? = null,
    // 思考模式的推理内容（thinking 开启时出现）
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
)

@Serializable
private data class DeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val function: DeltaFunction? = null,
)

@Serializable
private data class DeltaFunction(
    val name: String? = null,
    val arguments: String? = null,
)
