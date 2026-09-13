# 泡泡 · 安卓原生 AI 聊天搭子（DeepSeek 驱动）

「泡泡」是一款安卓原生 App：把 DeepSeek 大模型做成手机里的 agent harness，
可以创建多个性格各异的虚拟角色，与之单聊或群聊，角色会记住你告诉它的事。

**当前版本 v1.0.0**（2026-08-13）——使用说明见
[releases/泡泡-使用文档.txt](releases/泡泡-使用文档.txt)，技术设计见
[docs/TECH_DESIGN.md](docs/TECH_DESIGN.md)。

## 下载安装

安装包：[releases/泡泡-v1.0.0.apk](releases/泡泡-v1.0.0.apk)（Android 8.0 / API 26 及以上）

传到手机点击安装即可。首次打开需自备 DeepSeek API Key
（申请步骤见使用文档第三章，约 10 分钟）；Key 只保存在手机本地，请求直连 DeepSeek。

## 功能

- 💬 单聊 / 群聊：角色各自保持性格，群聊支持 @全体成员或 @个人
- 🎭 多角色系统：首次向导生成角色；头像、聊天背景、角色主页背景、签名、个性标签均可自定义
- 🧠 记忆系统：显式「记住……」+ 每 10 轮自动抽取关键事实；记忆按角色隔离，群聊内容群内成员共享
- 🔧 工具调用：提醒、便签、当前时间，执行过程以卡片展示
- 📝 消息操作：复制 / 引用 / 撤回 / 删除 / 多选（转发、导出、收藏）
- ⚙️ 设置：API Key / 模型 / 温度 / 思考模式，默认模型 `deepseek-v4-flash`

## 环境要求

- JDK 17+（本机建议用 JDK 21；Gradle 8.10 不支持 JDK 25）
- Android SDK（路径写入 `local.properties` 的 `sdk.dir`，该文件不入库）
- Android 真机或模拟器（API 26+）

## 构建与安装

```bash
# Windows（cmd / PowerShell / Git Bash 均可）
gradlew.bat assembleDebug          # 或 ./gradlew assembleDebug

# 安装到已连接的设备
gradlew.bat installDebug
```

也可直接用 Android Studio 打开工程目录（首次 Sync 会自动下载依赖，已配置阿里云镜像加速）。

> ⚠️ 签名密钥未随仓库提供：`releases/paopao.keystore` 已被 `.gitignore` 排除，
> 因此 `assembleRelease` 需要你自备密钥并替换 `app/build.gradle.kts` 中的 `signingConfigs`；
> 只是自己装机测试的话用上面的 `assembleDebug` 即可。

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
app/src/main/java/com/deepseekbuddy/app/
├── agent/            # harness：引擎、消息模型、工具系统
│   ├── llm/          # DeepSeek 客户端（SSE 流式）
│   ├── persona/      # 人设模型与模板
│   ├── context/      # 上下文预算、滚动摘要、记忆抽取、情绪识别
│   └── tools/        # 提醒 / 便签 / 记忆 / 时间
├── ui/               # chat / contacts / memory / notes / onboarding / personas / profile / settings / theme
├── data/             # SettingsStore（DataStore）与本地库
├── reminder/         # 提醒调度（AlarmManager + 通知）
├── worker/           # 后台任务
├── util/
└── MainActivity.kt
```

## 路线图

阶段 1（POC）→ 阶段 2（MVP：人设系统 + 记忆 + Room）→ 阶段 3（全部工具 + 确认机制 + 上下文预算）→ 阶段 4（个性化深化）→ 阶段 5（打磨发布），详见设计文档第 13 节。
