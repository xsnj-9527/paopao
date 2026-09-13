package com.deepseekbuddy.agent.ports

/**
 * 工具与应用数据层之间的端口。
 *
 * 抽这几个接口之前，`NoteTool` / `RememberFactTool` / `ReminderTool` 直接依赖
 * `NotesRepository(dao: AppDao)`、`MemoryRepository(dao: AppDao)`、
 * `ReminderScheduler(context: Context)` —— 三个具体类，其中两个还要 Android Context。
 * 结果是 Agent 内核**没法在纯 JVM 上跑**：评测要起模拟器才能跑一条用例。
 *
 * 现在内核只认这三个接口，App 侧提供 Room / AlarmManager 实现，
 * 评测与单测侧提供内存实现 —— 同一份工具逻辑，两边都跑得起来。
 */

/** 便签写入。App 侧是 Room `notes` 表。 */
interface NoteStore {
    suspend fun add(title: String, content: String)
}

/** 长期记忆写入。App 侧是 Room `memories` 表，按 personaId 隔离。 */
interface MemoryStore {
    // 默认值声明在接口上：Kotlin 不允许 override 再写一遍默认值，
    // 而 App 侧确实有只传 (personaId, content) 的调用点。
    suspend fun add(personaId: Long, content: String, category: String = "事实", importance: Int = 3)
}

/** 提醒调度。App 侧是 AlarmManager + 通知。 */
interface ReminderGateway {
    fun schedule(triggerAtMillis: Long, title: String)
}
