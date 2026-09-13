package com.deepseekbuddy.agent.eval

import com.deepseekbuddy.agent.AgentConfig
import com.deepseekbuddy.agent.ChatMessage
import com.deepseekbuddy.agent.ToolCall
import com.deepseekbuddy.agent.llm.ChatResponse
import com.deepseekbuddy.agent.llm.LlmClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ── 报告结构（同时也是回归基线的格式）────────────────────────────────────

@Serializable
data class Metrics(
    /** 工具选择正确率：工具序列完全一致且没碰禁止工具 */
    val toolSelectionAccuracy: Double,
    /** 参数正确率：声明了参数断言的用例里，全部断言通过的比例 */
    val argumentAccuracy: Double,
    /** 任务完成率：所有维度都通过的用例比例 */
    val taskCompletionRate: Double,
    /** 平均轮次：每次任务平均请求模型几次 */
    val avgRounds: Double,
    /** 平均估算 token（2 字符/token 口径） */
    val avgEstimatedTokens: Double,
)

@Serializable
data class CategoryMetrics(
    val category: String,
    val total: Int,
    val passed: Int,
    val completionRate: Double,
    val toolSelectionAccuracy: Double,
)

@Serializable
data class CaseResult(
    val id: String,
    val category: String,
    val difficulty: String,
    val passed: Boolean,
    val failures: List<String> = emptyList(),
    val attribution: String? = null,
    val attributionLabel: String? = null,
    val tools: List<String> = emptyList(),
    val rounds: Int = 0,
    val estimatedTokens: Int = 0,
    // 各个维度的判定单独留着，指标直接由它们算出来，
    // 不去反解 failures 的文案（那样一改文案指标就错）。
    val toolSelectionOk: Boolean = true,
    val forbiddenOk: Boolean = true,
    val argsOk: Boolean = true,
    val hasArgsExpectation: Boolean = false,
)

@Serializable
data class Report(
    val runAt: String,
    val mode: String,
    val model: String,
    val total: Int,
    val passed: Int,
    val metrics: Metrics,
    val byCategory: List<CategoryMetrics>,
    val attributionSummary: Map<String, Int>,
    val cases: List<CaseResult>,
)

// ── 命令行 ────────────────────────────────────────────────────────────────

private const val USAGE = """
泡泡 Agent 评测 runner

用法: runEval [选项]

  --cases <dir>       用例目录（默认 ../evals/cases）
  --out <dir>         报告输出目录（默认 ../evals/reports）
  --live              真实调用 DeepSeek（需要 DEEPSEEK_API_KEY）
  --model <name>      --live 时使用的模型
  --replay <file>     回放录制文件（确定性，可当回归基线）
  --record <file>     --live 时把模型回复录成回放文件
  --baseline <file>   与历史报告对比，输出回归差异
  --category <name>   只跑某一类用例
  --json              只打印 JSON 报告，不打印人读摘要

模式三选一：--live / --replay / 都不给则空跑（验证流水线本身）
"""

fun main(args: Array<String>) = runBlocking {
    val opts = Options.parse(args)
    if (opts.help) { println(USAGE.trim()); return@runBlocking }

    val caseFiles = loadCases(opts.casesDir, opts.category)
    if (caseFiles.isEmpty()) {
        System.err.println("没有找到用例：${opts.casesDir}")
        kotlin.system.exitProcess(2)
    }

    val mode = opts.resolveMode()
    val recorder = if (opts.recordPath != null && mode is EvalMode.Live) Recorder() else null
    val llmSource = if (recorder != null) recorder.wrap(LlmSources.of(mode)) else LlmSources.of(mode)

    val config = AgentConfig(apiKey = "eval-runner", model = opts.model)

    val results = mutableListOf<CaseResult>()
    val trajectories = mutableListOf<Pair<EvalCase, Trajectory>>()

    for (file in caseFiles) {
        for (case in file.cases) {
            val trajectory = EvalHarness.runCase(case, file.category, file.now, llmSource, config)
            val hasArgs = case.expect.args.isNotEmpty()
            val outcome = Evaluator.evaluate(case, file.category, trajectory, hasArgs)
            trajectories += case to trajectory
            results += CaseResult(
                id = case.id,
                category = file.category,
                difficulty = case.difficulty,
                passed = outcome.passed,
                failures = outcome.failures,
                attribution = outcome.attribution?.name,
                attributionLabel = outcome.attribution?.label,
                tools = trajectory.toolCalls.map { it.name },
                rounds = trajectory.rounds,
                estimatedTokens = trajectory.estimatedTokens,
                toolSelectionOk = outcome.toolSelectionOk,
                forbiddenOk = outcome.forbiddenOk,
                argsOk = outcome.argsOk,
                hasArgsExpectation = hasArgs,
            )
        }
    }

    val report = buildReport(results, mode, opts.model)

    opts.recordPath?.let { path ->
        recorder?.writeTo(File(path))
        println("已录制回放文件：$path（${recorder?.turnCount ?: 0} 轮）")
    }

    val outDir = File(opts.outDir).apply { mkdirs() }
    File(outDir, "latest.json").writeText(REPORT_JSON.encodeToString(Report.serializer(), report))
    File(outDir, "latest.md").writeText(renderMarkdown(report, trajectories, mode))

    if (opts.jsonOnly) {
        println(REPORT_JSON.encodeToString(Report.serializer(), report))
    } else {
        printSummary(report, mode)
        opts.baselinePath?.let { printRegression(File(it), report) }
        println("\n报告已写入：${File(outDir, "latest.md").path}")
    }

    // 有失败就用非零退出码，方便挂进 CI 当门禁
    kotlin.system.exitProcess(if (report.passed == report.total) 0 else 1)
}

// ── 各步骤实现 ────────────────────────────────────────────────────────────

private val REPORT_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }

private data class Options(
    val casesDir: String,
    val outDir: String,
    val live: Boolean,
    val model: String,
    val replayPath: String?,
    val recordPath: String?,
    val baselinePath: String?,
    val category: String?,
    val jsonOnly: Boolean,
    val help: Boolean,
) {
    fun resolveMode(): EvalMode = when {
        replayPath != null -> EvalMode.Replay(
            REPORT_JSON.decodeFromString(ReplayFile.serializer(), File(replayPath).readText()),
            recordPath,
        )
        live -> {
            val key = System.getenv("DEEPSEEK_API_KEY")
                ?: error("--live 需要环境变量 DEEPSEEK_API_KEY（不要写进代码或提交进仓库）")
            EvalMode.Live(key, model)
        }
        else -> EvalMode.NullModel
    }

    companion object {
        fun parse(args: Array<String>): Options {
            var cases = "../evals/cases"
            var out = "../evals/reports"
            var live = false
            var model = "deepseek-v4-flash"
            var replay: String? = null
            var record: String? = null
            var baseline: String? = null
            var category: String? = null
            var jsonOnly = false
            var help = false

            var i = 0
            while (i < args.size) {
                val a = args[i]
                fun next(): String {
                    require(i + 1 < args.size) { "$a 需要一个参数" }
                    i++
                    return args[i]
                }
                when (a) {
                    "--cases" -> cases = next()
                    "--out" -> out = next()
                    "--live" -> live = true
                    "--model" -> model = next()
                    "--replay" -> replay = next()
                    "--record" -> record = next()
                    "--baseline" -> baseline = next()
                    "--category" -> category = next()
                    "--json" -> jsonOnly = true
                    "-h", "--help" -> help = true
                    else -> error("未知参数：$a")
                }
                i++
            }
            return Options(cases, out, live, model, replay, record, baseline, category, jsonOnly, help)
        }
    }
}

private fun loadCases(dir: String, only: String?): List<CaseFile> {
    val files = File(dir).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
    return files.mapNotNull { f ->
        runCatching {
            REPORT_JSON.decodeFromString(CaseFile.serializer(), f.readText())
        }.getOrElse { e ->
            System.err.println("跳过 ${f.name}：${e.message}")
            null
        }
    }.filter { only == null || it.category == only }
}

/** internal 而不是 private：自测要直接验证指标计算，不去反解 Markdown。 */
internal fun buildReport(results: List<CaseResult>, mode: EvalMode, model: String): Report {
    val total = results.size
    val passed = results.count { it.passed }

    fun toolPicked(r: CaseResult) = r.toolSelectionOk && r.forbiddenOk
    val toolOk = results.count { toolPicked(it) }

    // 参数正确率的分母是「声明过参数断言的用例」—— 没断言的用例不该拉高这个指标。
    val argsDenom = results.filter { it.hasArgsExpectation }
    val argsOk = argsDenom.count { it.argsOk }

    val byCategory = results.groupBy { it.category }.map { (cat, list) ->
        CategoryMetrics(
            category = cat,
            total = list.size,
            passed = list.count { it.passed },
            completionRate = ratio(list.count { it.passed }, list.size),
            toolSelectionAccuracy = ratio(list.count { toolPicked(it) }, list.size),
        )
    }.sortedBy { it.category }

    return Report(
        runAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
        mode = mode.label,
        model = model,
        total = total,
        passed = passed,
        metrics = Metrics(
            toolSelectionAccuracy = ratio(toolOk, total),
            argumentAccuracy = ratio(argsOk, argsDenom.size),
            taskCompletionRate = ratio(passed, total),
            avgRounds = results.map { it.rounds }.average().takeIf { !it.isNaN() } ?: 0.0,
            avgEstimatedTokens = results.map { it.estimatedTokens }.average().takeIf { !it.isNaN() } ?: 0.0,
        ),
        byCategory = byCategory,
        attributionSummary = results.mapNotNull { it.attribution }
            .groupingBy { it }.eachCount().toSortedMap(),
        cases = results,
    )
}

private fun ratio(n: Int, d: Int): Double = if (d == 0) 0.0 else n.toDouble() / d

private fun printSummary(report: Report, mode: EvalMode) {
    val m = report.metrics
    println("=".repeat(64))
    println("泡泡 Agent 评测报告   模式=${mode.label}   模型=${report.model}")
    println("=".repeat(64))
    println("任务完成率        ${pct(m.taskCompletionRate)}   (${report.passed}/${report.total})")
    println("工具选择正确率    ${pct(m.toolSelectionAccuracy)}")
    println("参数正确率        ${pct(m.argumentAccuracy)}")
    println("平均轮次          ${"%.2f".format(m.avgRounds)}")
    println("平均估算 token    ${"%.0f".format(m.avgEstimatedTokens)}")
    println()
    println("按类别：")
    for (c in report.byCategory) {
        println("  ${c.category.padEnd(10)} ${c.passed}/${c.total}  完成率 ${pct(c.completionRate)}  工具选择 ${pct(c.toolSelectionAccuracy)}")
    }
    if (report.attributionSummary.isNotEmpty()) {
        println()
        println("失败归因分布：")
        for ((k, v) in report.attributionSummary) {
            val a = Attribution.valueOf(k)
            println("  ${a.label.padEnd(22)} $v  → ${a.howToFix}")
        }
    }
}

private fun printRegression(baselineFile: File, current: Report) {
    if (!baselineFile.exists()) {
        println("\n（基线文件不存在，跳过回归对比：${baselineFile.path}）")
        return
    }
    val base = REPORT_JSON.decodeFromString(Report.serializer(), baselineFile.readText())
    val baseById = base.cases.associateBy { it.id }
    val curById = current.cases.associateBy { it.id }

    val fixed = curById.filter { (id, c) -> c.passed && baseById[id]?.passed == false }.keys
    val broken = curById.filter { (id, c) -> !c.passed && baseById[id]?.passed == true }.keys
    val added = curById.keys - baseById.keys
    val removed = baseById.keys - curById.keys

    println()
    println("─".repeat(64))
    println("回归对比   基线：${baselineFile.name}（${base.runAt}，模式 ${base.mode}）")
    println("─".repeat(64))
    println("完成率   ${pct(base.metrics.taskCompletionRate)} → ${pct(current.metrics.taskCompletionRate)}  (${delta(current.metrics.taskCompletionRate - base.metrics.taskCompletionRate)})")
    println("工具选择 ${pct(base.metrics.toolSelectionAccuracy)} → ${pct(current.metrics.toolSelectionAccuracy)}  (${delta(current.metrics.toolSelectionAccuracy - base.metrics.toolSelectionAccuracy)})")
    println("参数正确 ${pct(base.metrics.argumentAccuracy)} → ${pct(current.metrics.argumentAccuracy)}  (${delta(current.metrics.argumentAccuracy - base.metrics.argumentAccuracy)})")
    println("平均轮次 ${"%.2f".format(base.metrics.avgRounds)} → ${"%.2f".format(current.metrics.avgRounds)}")
    println()
    println("新通过 ${fixed.size} 条：${fixed.sorted().joinToString(", ").ifEmpty { "无" }}")
    println("新失败 ${broken.size} 条：${broken.sorted().joinToString(", ").ifEmpty { "无" }}")
    if (added.isNotEmpty()) println("新增用例：${added.sorted().joinToString(", ")}")
    if (removed.isNotEmpty()) println("移除用例：${removed.sorted().joinToString(", ")}")
}

private fun pct(v: Double): String = "${"%.1f".format(v * 100)}%"

private fun delta(v: Double): String {
    val s = "%.1f".format(v * 100)
    return if (v > 0.0001) "+$s%" else if (v < -0.0001) "$s%" else "持平"
}

// ── 录制 ──────────────────────────────────────────────────────────────────

/** 把 --live 的模型回复录下来，写成一份可直接 --replay 的文件。 */
private class Recorder {
    private val perCase = mutableMapOf<String, MutableList<RecordedTurn>>()
    private var currentCase: String? = null
    val turnCount: Int get() = perCase.values.sumOf { it.size }

    fun wrap(inner: LlmSource): LlmSource = LlmSource { caseId, config ->
        currentCase = caseId
        object : LlmClient {
            override suspend fun chat(
                messages: List<ChatMessage>,
                tools: List<JsonObject>,
                onDelta: (String) -> Unit,
                onReasoning: (String) -> Unit,
            ): ChatResponse {
                val r = inner.clientFor(caseId, config).chat(messages, tools, onDelta, onReasoning)
                perCase.getOrPut(caseId) { mutableListOf() } += RecordedTurn(
                    text = r.text,
                    reasoning = r.reasoningText,
                    toolCalls = r.toolCalls.map { RecordedToolCall(it.id, it.name, it.argumentsJson) },
                )
                return r
            }
        }
    }

    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(
            REPORT_JSON.encodeToString(
                ReplayFile.serializer(),
                ReplayFile(note = "由 EvalRunner --record 录制于 ${LocalDateTime.now()}", cases = perCase),
            ),
        )
    }
}

// ── Markdown 报告 ─────────────────────────────────────────────────────────

private fun renderMarkdown(
    report: Report,
    trajectories: List<Pair<EvalCase, Trajectory>>,
    mode: EvalMode,
): String = buildString {
    val m = report.metrics
    appendLine("# 泡泡 Agent 评测报告")
    appendLine()
    appendLine("> 运行时间：${report.runAt}　|　模式：**${mode.label}**　|　模型：`${report.model}`")
    appendLine()
    appendLine("## 总体指标")
    appendLine()
    appendLine("| 指标 | 值 | 含义 |")
    appendLine("|---|---|---|")
    appendLine("| 任务完成率 | **${pct(m.taskCompletionRate)}** | 工具、参数、回答全部达标（${report.passed}/${report.total}） |")
    appendLine("| 工具选择正确率 | ${pct(m.toolSelectionAccuracy)} | 工具序列与期望完全一致，且没调用被禁止的工具 |")
    appendLine("| 参数正确率 | ${pct(m.argumentAccuracy)} | 声明的参数断言全部通过 |")
    appendLine("| 平均轮次 | ${"%.2f".format(m.avgRounds)} | 每个任务平均请求模型几次 |")
    appendLine("| 平均估算 token | ${"%.0f".format(m.avgEstimatedTokens)} | 输入上下文 + 工具 schema，2 字符/token 口径 |")
    appendLine()
    appendLine("## 分类表现")
    appendLine()
    appendLine("| 类别 | 通过 | 完成率 | 工具选择 |")
    appendLine("|---|---|---|---|")
    for (c in report.byCategory) {
        appendLine("| ${c.category} | ${c.passed}/${c.total} | ${pct(c.completionRate)} | ${pct(c.toolSelectionAccuracy)} |")
    }
    appendLine()

    val failed = report.cases.filter { !it.passed }
    appendLine("## 失败归因表（${failed.size} 条）")
    appendLine()
    if (failed.isEmpty()) {
        appendLine("本轮全部通过。")
    } else {
        appendLine("| 用例 | 归因 | 具体失败 |")
        appendLine("|---|---|---|")
        for (c in failed) {
            appendLine("| ${c.id} | ${c.attributionLabel ?: "未分类"} | ${c.failures.joinToString("<br>").replace("|", "\\|")} |")
        }
    }
    appendLine()
    if (report.attributionSummary.isNotEmpty()) {
        appendLine("### 归因分布与改法")
        appendLine()
        appendLine("| 归因 | 条数 | 该改哪里 |")
        appendLine("|---|---|---|")
        for ((k, v) in report.attributionSummary) {
            val a = Attribution.valueOf(k)
            appendLine("| ${a.label} | $v | ${a.howToFix} |")
        }
        appendLine()
    }

    appendLine("## 全部用例")
    appendLine()
    appendLine("| 用例 | 类别 | 期望工具 | 实际工具 | 轮次 | 结果 |")
    appendLine("|---|---|---|---|---|---|")
    for (c in report.cases) {
        val expect = trajectories.firstOrNull { it.first.id == c.id }?.first?.expect?.tools?.joinToString(",") ?: ""
        appendLine("| ${c.id} | ${c.category} | $expect | ${c.tools.joinToString(",").ifEmpty { "—" }} | ${c.rounds} | ${if (c.passed) "✅" else "❌"} |")
    }
    appendLine()
}
