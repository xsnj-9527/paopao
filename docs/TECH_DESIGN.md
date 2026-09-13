# DeepSeek 聊天搭子 · 安卓 Agent Harness 技术设计文档

| 项目 | 内容 |
|---|---|
| 版本 | v0.1（评审稿） |
| 日期 | 2026-08-13 |
| 范围 | L2：聊天搭子 + 人设 + 记忆 + 轻量工具（提醒/便签/打开应用） |
| 发布形态 | 自用侧载 APK（后续可评估商店） |
| 状态 | 待评审 → 评审通过后进入阶段 1（POC） |

---

## 1. 项目概述

### 1.1 目标

一款安卓原生 App：由 DeepSeek 驱动、可深度个性化的 AI 聊天搭子。
App 本质是一个 **agent harness**——负责「模型 → 工具调用 → 执行 → 回填上下文」循环的运行时，UI 只是它的前端。

### 1.2 非目标（v1 明确不做）

- 无障碍全操控（L3，架构预留扩展点，见 6.5）
- 语音对话 / 多模态
- 账号系统 / 云同步 / 多设备
- 社区 / 多人角色

### 1.3 关键指标

- 发送消息到首个 token 流式输出 ≤ 1.5s
- 工具调用解析成功率 ≥ 95%
- 7 天真机连续使用无崩溃

---

## 2. 总体架构

```
┌──────────────────────────────────────────────────┐
│ UI 层（Jetpack Compose / Material 3）             │
│   聊天页 · 人设页 · 记忆页 · 设置页 · 用量页       │
└──────────────────┬───────────────────────────────┘
                   │ ViewModel / StateFlow
┌──────────────────▼───────────────────────────────┐
│ Agent 层（harness 心脏）                          │
│   AgentEngine     循环状态机（流式/工具/确认/重入） │
│   ContextAssembler system prompt 拼装 + 预算裁剪   │
│   ToolRegistry    工具注册 / schema 转换 / 分发    │
│   MemoryService   记忆读写 / 抽取 / 注入           │
└──────┬─────────────────────────────┬─────────────┘
       │                             │
┌──────▼───────┐           ┌─────────▼───────────┐
│ LLM 客户端    │           │ 工具实现层           │
│ DeepSeek     │           │ Reminder/Note/      │
│ SSE 流式解析  │           │ OpenApp/Time        │
└──────┬───────┘           └─────────┬───────────┘
       │                             │
┌──────▼─────────────────────────────▼───────────┐
│ 数据层                                           │
│  Room：会话/消息/记忆/便签/提醒                  │
│  DataStore：设置/人设   Keystore：API Key        │
└─────────────────────────────────────────────────┘
```

依赖方向自上而下，Agent 层不直接依赖 UI；工具实现只依赖数据层接口（面向接口，便于单测）。

---

## 3. 技术栈

| 项 | 选型 | 说明 |
|---|---|---|
| 语言 | Kotlin 2.x | Compose 编译器插件 |
| UI | Jetpack Compose + Material 3 | 动态取色、深色模式 |
| 网络 | OkHttp 4.x + 自写 SSE 解析 | 仅 HTTPS，无其他网络 SDK |
| 序列化 | kotlinx-serialization | 工具 schema / 记忆抽取 JSON |
| 本地存储 | Room 2.x + DataStore Preferences | 结构化数据 / 设置 |
| 密钥 | Android Keystore（EncryptedSharedPreferences） | API Key |
| 后台 | WorkManager + AlarmManager | 定时关怀 / 提醒 |
| DI | Hilt | |
| 构建 | Gradle KTS + JDK 17 | AGP 8.x |
| 版本 | minSdk 26 / targetSdk 35 | 真机为主 |

---

## 4. Agent 核心循环（harness 心脏）

### 4.1 状态机

```
IDLE → BUILDING → STREAMING → TOOL_EXEC ──▶ CALLING_MODEL（迭代，≤5 轮）
                  │                │              │
                  ▼                ▼              ▼
                 DONE            CONFIRMING     ERROR / USER_STOP
                                （挂起等用户）
```

- 每轮工具迭代把工具结果作为 `tool` role 消息回填，**不重复流式输出**，只在最终轮输出可见文本
- 用户点击「停止」→ 中断流式 → 保存已输出内容
- 达到 5 轮工具迭代仍未完成 → 输出「我在这个任务上打转太久了，请再说得具体些」

### 4.2 消息模型

```kotlin
data class ChatMessage(
    val role: String,          // system / user / assistant / tool
    val content: String?,
    val toolCallId: String?,   // tool role 回填时使用
    val toolCalls: List<ToolCall>?  // assistant 发起工具调用
)
```

### 4.3 请求拼装

```
POST {baseUrl}/chat/completions
```

```json
{
  "model": "deepseek-v4-flash",
  "messages": [ ...由 ContextAssembler 产出... ],
  "tools": [ ...ToolRegistry 转换后... ],
  "temperature": 1.2,
  "stream": true
}
```

- **模型**：默认 `deepseek-v4-flash`（价格 ¥1/M in、¥2/M out）；设置页可切换 v4-pro
- **thinking**：日常对话关闭；检测到复杂任务关键词（规划/分析/对比）时置 thinking=true，复用同一模型名（V4 通过请求开关切换，旧名 `deepseek-chat`/`deepseek-reasoner` 已于 2026-07-24 弃用，不采用）
- **temperature**：默认 1.2，人设页可按风格调整（0.7 冷静 / 1.3 活泼）

### 4.4 流式与工具调用解析

1. SSE 逐行解析 `data: {...}`，文本增量直接进 UI；`delta.tool_calls` 按 index 累积合并
2. `finish_reason == "tool_calls"` → 解析 JSON 参数 → 进入 TOOL_EXEC
3. 执行/确认后追加 `tool` 消息 → 重新发起请求（携带完整历史，stream=false 也可，见下）

> 注意：工具迭代轮不需要流式（没有给用户看的文本），用非流式请求，速度更快、解析更简单。

### 4.5 错误处理

| 错误 | 处理 |
|---|---|
| 429 / 5xx | 指数退避重试（1s/2s/4s，最多 3 次） |
| 401 | 提示「API Key 无效，请到设置页检查」 |
| 连接超时（10s）/ 流空闲超时（60s） | 报「网络超时，请重试」，保留草稿 |
| 无网络 | 离线提示，不丢上下文 |

所有错误文案为中文、可操作，绝不让用户看到堆栈。

---

## 5. 记忆系统设计（核心差异化，投入重点）

### 5.1 三层记忆模型

```
┌─────────────────────────────────────────────┐
│ ① 人设卡（Persona）——静态，用户显式配置       │
│    身份/关系/语气/风格/边界/背景/示例回复      │
├─────────────────────────────────────────────┤
│ ② 长期记忆（Memories）——事实条目，可增删改    │
│    写入：显式「记住…」+ 会话后自动抽取         │
├─────────────────────────────────────────────┤
│ ③ 短期记忆——原始消息 + 滚动摘要               │
│    最近 ~20 轮原始保留，更早的压成摘要         │
└─────────────────────────────────────────────┘
```

### 5.2 人设卡 schema（DataStore 存 JSON）

```json
{
  "name": "小煤",
  "relationship": "损友",
  "tone": "毒舌但关心",
  "style": "短句、爱用表情、会怼人",
  "boundaries": ["不讨论政治", "用户低落时先安慰再损"],
  "background": "和用户是大学室友",
  "exampleReplies": ["就你？算了吧哈哈", "……说重点，我记着呢"]
}
```

预设模板（可一键套用再微调）：温柔朋友 / 毒舌损友 / 学习搭子 / 树洞 / 工作助手。

### 5.3 长期记忆（Room 表 `memories`）

字段：`content, category(偏好|事实|关系|任务), importance(1-5), pinned, createdAt, lastUsedAt, sourceConvId`

写入途径：
1. **显式记忆**：用户说「记住…」→ 正则优先 + LLM 兜底结构化
2. **自动抽取**：会话结束或每 10 轮，后台任务跑固定抽取 prompt：

```
从对话中提取值得长期记住的关于用户的事实，只输出 JSON 数组：
[{"content":"...","category":"偏好","importance":4}]
无新事实输出 []
```

- 去重合并：与新条目 content 相似（关键词/字符重叠）则合并，importance 取高
- **失败静默**：抽取失败不影响主流程，下次会话再补
- 抽取任务在小模型（flash）上跑，成本可忽略

### 5.4 注入策略（ContextAssembler）

System prompt 固定模板：

```
你是「{name}」，用户的{relationship}。

【人设】{persona 全字段}

【关于用户】{pinned 记忆 + 按 importance×(1-时间衰减) 排序取 TopK}

【当前会话背景】{滚动摘要（如有）}

【规则】
- 全程中文，口语化，符合人设语气
- 不确定的事明说不知道，不编造
- 需要动手操作（提醒/便签/打开应用）时调用对应工具
```

- 长期记忆注入预算 **≤ 2k token**：pinned 优先 → importance → 最近使用
- 每条记忆带时间戳，用户可删除、编辑、固定
- 记忆管理页是产品级功能，不是开发者后门

### 5.5 隐私控制

- 设置页「隐私模式」：关闭自动抽取，仅保留显式记忆
- 记忆全存本地，App 内明示「对话内容会发送至 DeepSeek API」

---

## 6. 工具系统

### 6.1 接口设计

```kotlin
enum class RiskLevel { NONE, CONFIRM, FORBIDDEN }

interface Tool {
    val name: String
    val description: String          // 给模型看的自然语言说明
    val parameters: JsonObject       // JSON Schema（OpenAI function 格式）
    val riskLevel: RiskLevel
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val message: String,             // 回填给模型的执行结果描述
    val data: JsonObject? = null
)
```

`ToolContext`：数据层访问入口 + 当前会话信息 + 取消令牌。

### 6.2 注册与转换

`ToolRegistry` 持有 `Map<String, Tool>`，提供 `toOpenAiSchema()` 把工具集转换成：

```json
{ "type": "function",
  "function": { "name": "...", "description": "...",
                "parameters": { "type": "object", "properties": {...}, "required": [...] } } }
```

### 6.3 v1 内置工具

| 工具 | 参数 | 风险 | 实现 |
|---|---|---|---|
| `create_reminder` | title, triggerAt(ISO8601) | NONE | 精确 AlarmManager（降级 inexact）+ 通知 |
| `create_note` | title, content | NONE | Room `notes` 表 |
| `get_time` | — | NONE | 本地时间，防模型时间幻觉 |

v1 不提供：发短信、打电话、删除文件、**打开应用**（曾实现后由用户取消——聊天搭子定位不需要；QUERY_ALL_PACKAGES 权限已移除）等。

> 确认机制（CONFIRM/FORBIDDEN 风险分级 + 引擎挂起等待用户）保留为安全基础设施，未来新增工具按风险分级即可。

### 6.6 App 内闹钟（已取消）

> **App 内闹钟方案（方案 B）已于阶段 3 前由用户取消**，不再开发。原系统闹钟方案（方案 A）在 POC 阶段已取消（OPPO/ColorOS 对三方闹钟干预不可控）。提醒功能保留**应用内通知路径**（精确闹钟降级为 inexact set()）。后续如需可靠响铃，重新评估方案。

### 6.4 确认机制

- `CONFIRM` 工具：循环挂起 → 弹确认卡片（工具名 + 参数人话化，如「搭子想打开：微信」）→ 允许则执行并回填结果；拒绝则回填 `用户拒绝了该操作`
- `FORBIDDEN`：不执行，直接回填「该操作已被用户禁用」
- 所有工具调用/结果写入 `messages.toolCallsJson`，可回溯

### 6.5 L3 扩展点（本期不实现，接口已预留）

无障碍工具以同一 `Tool` 接口接入：`click_element` / `input_text` / `scroll`（基于 a11y 树解析）——只新增实现 + 注册，循环层零改动。

---

## 7. 上下文管理策略

| 项 | 值 |
|---|---|
| 对话窗口预算 | 默认 16k token，硬上限 32k |
| 裁剪顺序 | ① 最早的一对 user/assistant 消息 → ② 更早的压成摘要回填 → ③ 仍超限则丢弃次早期消息 |
| token 估算 | 中文约 1 token / 1.5 字，按 2 字符/token 保守估算 |
| 用量记录 | 每轮记录 prompt/completion tokens（Room），用量页展示 + 月度费用估算 |
| 熔断 | 单会话累计费用超阈值 → 弹提示并暂停自动续聊 |

flash 虽支持 1M 上下文，但出于**延迟与成本**考虑设 16k 预算；预算值可在设置页调整。

---

## 8. 数据模型（Room）

```
conversations(id, title, model, summary, createdAt, updatedAt)
messages(id, convId, role, content, toolCallsJson, createdAt, tokens)
memories(id, content, category, importance, pinned, createdAt, lastUsedAt, sourceConvId)
notes(id, title, content, createdAt, updatedAt)
reminders(id, title, triggerAt, repeatRule, done)
```

设置与人设走 DataStore（JSON / Preferences）；API Key 走 Keystore 加密存储。

---

## 9. UI 设计（Compose）

| 页面 | 要点 |
|---|---|
| 聊天页 | 气泡（用户右/搭子左）、流式光标、「停止」按钮、工具调用卡片（调用中/成功/被拒绝）、确认弹窗、会话列表入口 |
| 人设页 | 预设模板卡片 + 字段表单 + 示例回复预览 + temperature 滑杆 |
| 记忆页 | 按 category 分组、搜索、编辑/删除/固定/全部清空 |
| 设置页 | API Key（掩码）、模型选择、thinking 开关、预算、隐私模式、主动关怀开关与时段 |
| 用量页 | 会话/月度 token 数与估算费用 |

导航：底部 4 tab（聊天 / 人设 / 记忆 / 设置），用量页从设置进入。

---

## 10. 主动关怀（v1 简化版）

- WorkManager periodic 15 分钟（Android 最短周期）
- 触发条件（全满足）：开关开启 && 当前在「陪伴时段」&& 距上次对话 > 4h && 存在可提醒记忆（如「周末提醒我买猫粮」）
- 通知用 `MessagingStyle` 快捷回复，回复直达新会话
- 明示局限：Android 后台约束下不承诺准点，被系统杀进程属正常，WorkManager 会兜底重启

---

## 11. 安全与隐私

- API Key：Keystore 加密存储、界面掩码、日志禁止输出
- 最小权限：INTERNET、POST_NOTIFICATIONS、USE_EXACT_ALARM（可选）；不申请联系人/短信等敏感权限
- App 内隐私说明页：明示数据流向（本地存储 + DeepSeek API 推理）
- 记忆删除 = 物理删除（Room 事务）

---

## 12. 工程结构（单模块 + 分包）

```
app/src/main/java/com/deepseekbuddy/app/
├── MainActivity.kt / navigation
├── ui/
│   ├── chat/     聊天页（气泡、流式、工具卡片、确认弹窗）
│   ├── persona/  人设编辑页
│   ├── memory/   记忆管理页
│   ├── settings/ 设置页
│   └── usage/    用量页
├── agent/                    ← harness
│   ├── AgentEngine.kt        循环状态机
│   ├── ContextAssembler.kt   system 拼装 + 记忆注入
│   ├── TokenBudget.kt        预算估算/裁剪
│   ├── llm/DeepSeekClient.kt / SseParser.kt / ChatModels.kt
│   └── tools/Tool.kt, ToolRegistry.kt, tools/{Reminder,Note,OpenApp,Time}Tool.kt
├── memory/
│   ├── PersonaStore.kt / MemoryRepository.kt
│   ├── MemoryExtractor.kt / MemoryInjector.kt
├── data/local/  Room（entities/daos/Db）、DataStore
├── worker/ProactiveWorker.kt
├── di/AppModule.kt
└── util/
```

单模块起步（solo 项目，避免多模块构建开销）；包内依赖单向（ui → agent → data），后续拆模块只需按包搬移。

---

## 13. 里程碑与验收

| 阶段 | 内容 | 验收标准 | 工期 |
|---|---|---|---|
| 0 评审 | 设计文档定稿 | 评审通过 | ✅ 已完成 |
| 1 POC | 脚手架 + API 连通 + 流式渲染 + 提醒工具跑通 | 自然语言创建提醒成功（应用内通知）；系统闹钟方案实测不可行已取消 | ✅ 已完成 |
| 2 MVP v1 | 多角色系统 + 首次向导 + 三句话速写 + 记忆（按角色隔离）+ 【我的】页 + 设置完善 | 多角色各说各话、记忆按角色独立、主题/头像/背景可定制 | ✅ 已完成 |
| 3 Agent 工具层 | 便签 + 上下文预算（TokenBudget + 滚动摘要）+ 确认机制（CONFIRM/FORBIDDEN 安全基础设施） | 10 步内多轮工具任务不崩；危险操作必须经用户确认 | ✅ 已完成 |
| 4 个性化深化 | 记忆自动抽取 + 日历日记（30 轮改 20 轮）+ 行为画像 + 情绪闪念 + 时空胶囊 | 有记忆 vs 无记忆差异显著；胶囊 ≤2 次/天不骚扰 | ✅ 已完成 |
| 5 打磨发布 | 错误处理、用量页、隐私页、签名、侧载包 | 7 天真机连续使用无崩溃 | 2~3 周 |

> 注：App 内闹钟（方案 B）已由用户取消（见 6.6），阶段 3 不再包含闹钟模块。

---

## 14. 测试策略

**单元测试（JVM，MockWebServer）**
- ContextAssembler：注入顺序、预算裁剪、pinned 优先
- TokenBudget：估算与裁剪边界
- ToolRegistry：schema 转换正确性
- AgentEngine：模拟流式 + 工具调用 + 拒绝确认的完整循环

**真机手动清单**
流式/停止/重试 · 确认弹窗 · 提醒准时触发 · 通知快捷回复 · 后台 2h 不被杀 · 飞行模式下错误提示 · 深色模式

**持续验证**：阶段 4 起，每周用同一组对话测试记忆抽取质量。

---

## 15. 风险与预案

| 风险 | 概率 | 影响 | 预案 |
|---|---|---|---|
| 工具参数幻觉（时间/名称错） | 中 | 高 | 执行前确认 + `get_time` 工具 + 参数校验 |
| 长对话上下文漂移 | 中 | 中 | 滚动摘要 + pinned 记忆 + 预算裁剪 |
| 记忆抽取噪音/重复 | 中 | 低 | importance 门槛 + 去重合并 + 用户可删 |
| 成本超预期 | 低 | 中 | 预算熔断 + 用量看板 + flash 默认 |
| 后台被系统杀 | 高 | 低 | WorkManager 兜底 + 主动关怀容错 |
| API 变更/限流 | 低 | 中 | 模型名/baseUrl 配置化，支持多 endpoint |

---

## 16. 附录

### 16.1 DeepSeek API 要点（2026-08 现状）

- base_url：`https://api.deepseek.com`，OpenAI 兼容，国内直连
- 模型：`deepseek-v4-flash`（¥1/M in、¥2/M out，2500 并发）/ `deepseek-v4-pro`（¥3/M in、¥6/M out）
- 均支持 tool calling（含并行）、1M 上下文、流式、缓存命中输入价更低
- thinking 模式通过请求开关切换（旧名 deepseek-chat / deepseek-reasoner 已于 2026-07-24 弃用）
- 文档：https://api-docs.deepseek.com

### 16.2 参考项目

- Mobilerun（LLM 无关移动 agent 框架，a11y 树 + 视觉）：https://github.com/droidrun/mobilerun
- PrivateAgent（Flutter + DeepSeek + 无障碍）：https://github.com/orailnoor/private-agent

---

## 17. 迭代计划 v1.1（已排期）

> v1.0.0 已发布（2026-08-13）。以下两项为上下文记忆兜底的关键改进，来自代码评审。

### 17.1 滚动摘要合并（修复"旧摘要被覆盖"）

**问题**：`RollingSummarizer` 每次只基于最近窗口原文压缩，覆盖旧 `conversations.summary`，超过窗口的早期内容彻底丢失。

**方案**：
- 压缩输入改为「**旧摘要 + 新窗口原文**」，用合并式 prompt（复用 DayDiaryUpdater 的"已有日记 + 新对话"模式）输出**累积摘要**
- **硬上限（必配）**：输出后本地强制截断 **300 字**（句号边界）；输入侧旧摘要先截到 300 字再喂压缩器——防止"旧摘要+新摘要"递归合并无限膨胀
- 日记同样套用输入/输出截断

涉及：`RollingSummarizer`、`ChatViewModel.send()` 预算块、`DayDiaryUpdater`。

### 17.2 抽取滑窗修复（修复"早期事实漏网"）

**问题**：`MemoryExtractor` 每 10 轮只看最近 10 轮——第 N 轮提过且不再提的事实，在 N+10 轮检查时已滑出窗口，永不入库。

**方案**：
- conversations 表新增**抽取游标列**（上次抽取到的消息 id，迁移 v9）
- 抽取窗口 =「(游标, 最新消息]」，每次抽取后推进游标——**每条消息恰好被抽取一次**，无滑窗漏网
- 去重逻辑保留（按 personaId 内容去重）

涉及：`AppDatabase`（迁移 v9）、`ConversationEntity`、`MemoryExtractor`、`ChatViewModel.launchPostTurnTasks`。

### 17.3 自我披露规则截胡（最便宜的兜底，先行实施）

**问题**：LLM 抽取有窗口依赖；关键事实若在窗口外即漏。

**方案**：
- 新增本地规则抽取器：正则匹配自我披露模式「我叫/我是/我喜欢/我不喜欢/我爱/我讨厌/我养了/我家/我住在/我今年/我生日/我在…工作」等
- **每条用户消息即时匹配**（零 API、零窗口依赖），命中即按句子边界取最小子句入库
- 分类自动映射：我喜欢X→偏好，我养了/我家→事实，其余→事实
- 规则打不到的再由 17.2 的 LLM 游标抽取补位

涉及：新文件 `MemoryExtractor`（规则层）、`ChatViewModel`。

### 17.4 群聊 JSON 结构化分段（替代脆弱的正则切分）

**问题**：`splitGroupReply` 按【名字】正则切分——角色名含【】即崩；模型不守格式时切分失败。

**方案**：
- 群聊最终文字轮启用 `response_format: {"type":"json_object"}`，要求输出 `[{"speaker":"名字","text":"内容"}]`
- 解析失败重试 1 次，再失败**退回现有正则切分**
- **注意**：json_object 与 tools 组合可能互斥——仅最终文字轮启用，工具迭代轮保持普通模式
- JSON 包裹增加约 10~20% 输出 token，可接受

涉及：`DeepSeekClient`（response_format 参数）、`ChatViewModel`（群聊请求与分段逻辑）。

### 17.5 角色信息边界显式注入（零成本压串味）

**问题**：群聊成员共享同一个模型，容易串味（阿凯说出糖糖私聊才知道的事）。

**方案**：`buildGroupPrompt` 每个成员段补充两段显式声明：
- 【知道的事】：自己的记忆 + 群聊共享记忆（现有）
- 【信息边界】："你不知道 {其他成员名} 在私聊中与你无关的事，也不会提起；群聊里大家共同知道的事你都知道"

**诚实说明**：同一模型本质"全知道"，这是行为约束而非真实隔离，但对压制串味有效、零成本，先行实施。

涉及：`ChatViewModel.buildGroupPrompt`。

### 验收标准

- 长会话（>50 轮）中第 5 轮提到的唯一事实，会话结束 20 轮后仍能在记忆页找到
- 连续多次压缩后，第 1 次压缩前的关键事件仍出现在最新摘要中，且摘要长度始终 ≤ 300 字
- 用户说「我喜欢吃辣的」后，即时（不等到 10 轮）出现在记忆页
- 群聊中角色名含「【】」时分段仍正确；成员不提及他人私聊信息
