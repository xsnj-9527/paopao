# 泡泡 · 安卓原生 AI Agent Harness

> 一款把大模型装进手机的安卓原生应用。
> **App 本体是一个 agent harness** —— 负责「模型 → 工具调用 → 执行 → 上下文回填」循环的运行时，UI 只是它的前端。

**Kotlin 2.x · Jetpack Compose · Room · OkHttp（手写 SSE）· 不引入任何 LLM SDK**

**71 个 Kotlin 文件 / 10,101 行** —— 应用 49 个 / 7,056 行 · 内核 12 个 / 713 行 · 单测 10 个 / 2,332 行 · **18 个 Compose 界面**

> 这些数字都可以自己数：`find . -name "*.kt" | wc -l`、
> `find app/src/main -name "*.kt" -exec cat {} + | wc -l`、
> `find . -name "*Screen.kt" | wc -l`。

> 📌 **依赖注入用的是手写应用级容器，不是 Hilt。** 见 `data/PersonaRepository.kt` 的注释
> 「应用级容器：手动 DI（Hilt 留到后续阶段）」—— 够用，就省掉一个编译期注解处理器。

**当前版本 v1.0.0**（2026-08-13）
安装包：[releases/泡泡-v1.0.0-agent-core.apk](releases/泡泡-v1.0.0-agent-core.apk)（Android 8.0 / API 26 及以上）
　　↳ 这是**抽出 `agent-core` 内核之后**的构建，与旧包同签名、可直接覆盖升级
旧版安装包：[releases/泡泡-v1.0.0.apk](releases/泡泡-v1.0.0.apk)（保留用于回滚）
技术设计：[docs/TECH_DESIGN.md](docs/TECH_DESIGN.md)（17 章）
使用说明：[releases/泡泡-使用文档.txt](releases/泡泡-使用文档.txt)

---

## 仓库里的三条线

| 目录 | 是什么 | 怎么验 |
|---|---|---|
| `app/` | 安卓应用本体 | `./gradlew :app:assembleDebug` |
| **`agent-core/`** | **Agent 内核抽成的纯 JVM 库**——零 Android 依赖，可脱离模拟器单测 | `cd agent-core && ./gradlew test` → 55 项 |
| **`evals/`** | **Agent 评测框架**：33 条用例 + 5 个指标 + 轨迹采集 + 失败归因 + 回归对比 | `cd agent-core && ./gradlew runEval` |

抽 `agent-core` 之前，跑一次 Agent 循环要 Room + AlarmManager + 模拟器 + 真实 API Key；
现在纯 JVM、不联网也能跑，**评测才因此成为可能**。真实模型基线（`deepseek-v4-flash`）：
任务完成率 **87.9%**（29/33）、参数正确率 **100%**、平均 1.94 轮 —— 详见 `evals/metrics.md`。

---

## 它到底在做什么

市面上大多数「AI 聊天 App」是一层套在 HTTP 接口外面的 UI。这个项目的重点是**接口下面那一层**：

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
│ 手写 SSE 解析 │           │ OpenApp/Time        │
└──────┬───────┘           └─────────┬───────────┘
       │                             │
┌──────▼─────────────────────────────▼───────────┐
│ 数据层                                           │
│  Room：会话/消息/记忆/便签/提醒                  │
│  DataStore：设置/人设   Keystore：API Key        │
└─────────────────────────────────────────────────┘
```

依赖方向自上而下，Agent 层不依赖 UI；工具实现只依赖数据层接口。

---

## 核心实现

### 1. Agent 循环（`agent/AgentEngine.kt`）

自研循环状态机，覆盖「拼装消息 → 请求模型 → 若发起工具调用则执行并回填 → 重入」全链路：

- **多轮工具调用**：最多 `MAX_TOOL_ITERATIONS = 5` 轮。到顶后不是静默返回，而是给用户补一句可理解的话，避免无限烧 token。
- **风险分级**：`Tool` 上带 `RiskLevel`（`NONE` / `CONFIRM` / `FORBIDDEN`）。引擎执行前查一次——`FORBIDDEN` 直接拦截并回填「该操作已被用户禁用」；`CONFIRM` 挂起并弹 UI 等用户确认，拒绝则回填「用户拒绝了该操作」。**权限做在 harness 里而不是散在各个工具里**，这样新增工具时不需要重新想一遍权限问题。
- **失败不炸循环**：工具异常被包装成 `ToolResult.fail(...)` 回填给模型，让模型自己决定重试、换工具还是告诉用户；参数 JSON 解析失败时退化成空 `JsonObject`，交给工具报「缺参数」。唯一例外是 `CancellationException` 必须原样重抛，否则协程取消语义会被吞掉。

### 2. 流式客户端（`agent/llm/DeepSeekClient.kt`）

基于 OkHttp **手写 SSE 解析**，不引入任何 LLM SDK——协议本身就一个 POST 加一段行解析，自己写能精确控制超时、取消与错误体解析。

开发中踩到并修掉的两个**静默失败**（都不报错，只是功能不工作）：

- **工具调用参数是分片到达的**。`delta.tool_calls` 每项带一个 `index`，`id` 和 `name` 只在第一片出现，`arguments` 是字符串碎片。必须按 `index` 归并、用 `StringBuilder` 累积，**流结束后**才能拿到完整 JSON——拼早了就是半截 JSON，工具永远调不起来。
- **字段名映射**。JSON 是 `tool_calls`（下划线）、Kotlin 属性是 `toolCalls`，不加 `@SerialName` 映射的话字段静默为 `null`，同样不报错。

另外，协程取消通过 `coroutineContext[Job]?.invokeOnCompletion { call.cancel() }` 与 OkHttp 生命周期绑定；被 cancel 后 `execute()` 抛出的 `IOException` 要识别并转回 `CancellationException`，不能当成网络错误提示用户。

### 3. 工具系统（`agent/tools/`）

`Tool` 抽象 + `ToolRegistry` 注册中心：运行时把工具声明编译成 **JSON Schema** 注入系统提示词，统一负责参数反序列化、执行、结果回填与失败处理。

内置四类工具：`create_reminder`（AlarmManager + 通知）、`create_note`、`remember_fact`、`get_time`。调用过程以卡片形式回显在会话流里，用户可感知、可追溯。

### 4. 上下文工程（`agent/context/`）

| 组件 | 职责 |
|---|---|
| `TokenBudget` | token 估算（中文按 2 字符/token **保守估**）、历史超阈值触发压缩、裁剪保留最近尾部 |
| `RollingSummarizer` | 滚动摘要——管「最近聊了什么」 |
| `MemoryExtractor` | 每 N 轮抽取关键事实落 Room，按角色分区——管「关于你的事实」，**不参与裁剪**，走单独注入通道 |
| `EmotionDetector` / `BehaviorAnalyzer` | 情绪与行为分析 |

摘要与记忆**职责分离**：摘要会被裁掉，记忆不会。这是解决「聊久了就记不住事」的关键设计。

### 5. 多角色与群聊

人设模型支持头像 / 签名 / 个性标签 / 聊天背景全自定义。群聊中每个角色维护独立人格与独立记忆，群内成员共享群记忆，支持 @全体 / @个人的发言路由与轮次调度。

### 6. 安全

API Key 经 **Android Keystore** 加密后仅存本地，请求直连模型厂商，**App 不经过任何自建中转服务器**——不为用户托管密钥。

---

## 功能

- 💬 单聊 / 群聊：角色各自保持性格，群聊支持 @全体成员或 @个人
- 🎭 多角色系统：首次向导生成角色；头像、聊天背景、角色主页背景、签名、个性标签均可自定义
- 🧠 记忆系统：显式「记住……」+ 每 10 轮自动抽取关键事实；记忆按角色隔离，群聊内容群内成员共享
- 🔧 工具调用：提醒、便签、当前时间，执行过程以卡片展示
- 📝 消息操作：复制 / 引用 / 撤回 / 删除 / 多选（转发、导出、收藏）
- ⚙️ 设置：API Key / 模型 / 温度 / 思考模式，默认模型 `deepseek-v4-flash`

---

## 环境要求

- JDK 17+（本机建议用 JDK 21；Gradle 8.10 不支持 JDK 25）
- Android SDK（路径写入 `local.properties` 的 `sdk.dir`，该文件不入库）
- Android 真机或模拟器（API 26+）

> `agent-core/` 通过 `settings.gradle.kts` 里的 `includeBuild("agent-core")` 组合进主工程，
> **不需要额外配置**，Gradle 会自动一起构建。想单独跑内核的单测就直接 `cd agent-core && ./gradlew test`。

## 构建与安装

```bash
# Windows（cmd / PowerShell / Git Bash 均可）
gradlew.bat assembleRelease        # 覆盖升级用这个（需自备密钥，见下）
gradlew.bat assembleDebug          # 只是首次安装、手机上没装过旧版时才用它

gradlew.bat installRelease         # 安装到已连接的设备
```

也可直接用 Android Studio 打开工程目录（首次 Sync 会自动下载依赖，已配置阿里云镜像加速）。

> ⚠️ **`assembleDebug` 生成的包装不到已经装过 release 版的手机上。**
> debug 包用 SDK 自动生成的调试密钥签名（`CN=Android Debug`），而 release 包用的是你自己的密钥——
> 包名相同而签名不同，Android 判定为「同一个应用被冒充」，直接拒装。
>
> 三条路，按推荐顺序：
> 1. **用 release 密钥构建**：把 `paopao.keystore` 放到 `releases/` 下，跑 `assembleRelease` ——
>    **可覆盖升级，App 数据（便签 / 记忆 / 人设）全部保留**。这是换机测试推荐的唯一方式。
> 2. **给 debug 包换个包名**：在 `app/build.gradle.kts` 的 `buildTypes.debug` 里加
>    `applicationIdSuffix = ".debug"`，就能和旧版并存安装、互不影响。
> 3. 先彻底卸载旧版再装 —— 但**会清空所有数据**，且如果开了「应用双开/手机分身」，
>    双开出来的副本是独立包，得一并卸载。

> ⚠️ 签名密钥未随仓库提供：`releases/paopao.keystore` 与 `releases/KEYSTORE_INFO.md`
> （内含明文口令）都已被 `.gitignore` 排除。`assembleRelease` 需要你自备密钥并替换
> `app/build.gradle.kts` 中的 `signingConfigs`。
> **密钥丢了就再也无法给已安装的 App 升级**，只能卸载重装、数据清空 —— 务必备份。

## 首次使用

1. 打开 App → 自动进入设置页
2. 填入 DeepSeek API Key（[platform.deepseek.com](https://platform.deepseek.com) 申请，充值几块钱足够开发期测试）
3. 允许通知权限（提醒功能需要）
4. 回到聊天页，试试：

```
帮我设一个 3 分钟后的提醒，提醒我喝水
```

成功的话会看到工具调用卡片（🔧 create_reminder → 成功），3 分钟后收到通知。

## 常见问题

| 问题 | 处理 |
|---|---|
| 构建超时/依赖下载失败 | 已配置阿里云 + 腾讯云镜像；确认网络可访问 maven.aliyun.com |
| API 401 | 检查 API Key 是否复制完整（`sk-` 开头） |
| 提醒不触发 | 确认已允许通知权限；应用内提醒受厂商后台策略影响（OPPO/ColorOS 需在电池/后台管理里允许 App 后台运行）；阶段 3 将提供 App 内闹钟（前台服务 + 自定义铃声） |
| 模型名报错 | 设置页改为 `deepseek-v4-flash`（旧名 deepseek-chat/reasoner 已于 2026-07 弃用） |

## 目录结构

```
paopao/
├── app/                              # 安卓应用
│   └── src/main/java/com/deepseekbuddy/app/
│       ├── agent/persona/            # 人设模型与模板
│       ├── agent/context/            # 上下文预算、滚动摘要、记忆抽取、情绪识别
│       ├── agent/LogcatLogger.kt     # 把内核的日志出口接到 Logcat
│       ├── ui/                       # chat / contacts / memory / notes / onboarding / personas / profile / settings / theme
│       ├── data/                     # SettingsStore + Room（并实现内核的三个端口）
│       ├── reminder/                 # 提醒调度（AlarmManager + 通知，实现 ReminderGateway）
│       ├── worker/                   # 后台任务
│       └── MainActivity.kt
│
├── agent-core/                       # ★ Agent 内核（纯 JVM，零 Android 依赖）
│   └── src/
│       ├── main/kotlin/com/deepseekbuddy/agent/
│       │   ├── AgentEngine.kt        # 循环状态机
│       │   ├── AgentLogger.kt        # 日志出口抽象
│       │   ├── ChatModels.kt         # 消息 / 工具调用 / 配置
│       │   ├── llm/                  # LlmClient 契约 + DeepSeek 流式实现
│       │   ├── ports/                # NoteStore / MemoryStore / ReminderGateway
│       │   └── tools/                # 四个内置工具 + 注册表
│       └── test/                     # 55 项单测 + 评测 runner（2332 行）
│
└── evals/                            # ★ 评测框架
    ├── cases/                        # 33 条用例（时间/便签/记忆/提醒/组合）
    ├── replay/                       # 录制的模型回复，用于确定性回归
    ├── reports/                      # 指标报告与失败归因表
    └── metrics.md                    # 5 个指标 + 6 类归因的定义
```

## 路线图

阶段 1（POC）→ 阶段 2（MVP：人设系统 + 记忆 + Room）→ **阶段 2.5（内核抽库 + 评测框架）** → 阶段 3（全部工具 + 确认机制 + 上下文预算）→ 阶段 4（个性化深化）→ 阶段 5（打磨发布），详见设计文档第 13 节。

## 已知限制

- **UI 层没有自动化测试**（`app/` 下无 test 源码集），回归仍靠手动验证。内核侧已有 55 项单测全程覆盖。
- ~~Agent 层可观测性只有日志，没有 trace 与指标~~ → **内核侧已补**：`paopao-server`（同契约的 TS 实现）提供结构化 trace；安卓侧仍是 Logcat。
- ~~尚未做 Agent 效果评测~~ → **已完成**，见 `evals/`：33 条用例、5 个指标、失败归因表、回归对比。
  真实模型（`deepseek-v4-flash`）基线：任务完成率 **87.9%**、参数正确率 **100%**、平均 1.94 轮。
- **App 侧依赖装配没有测试**：`app.container` 那套手写 DI 只经过编译检查，没有运行时验证。

---

## License

[MIT](LICENSE)
