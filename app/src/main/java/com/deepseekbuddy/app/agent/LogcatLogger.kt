package com.deepseekbuddy.app.agent

import android.util.Log
import com.deepseekbuddy.agent.AgentLogger

/**
 * `AgentLogger` 的 Android Logcat 实现。
 *
 * `:agent-core` 刻意不依赖 Android，所以日志出口由 App 侧注入。
 * 不注入的话默认是 `AgentLogger.None` —— 内核会安静地什么都不打，
 * 这在真机上排查问题时是灾难，所以 App 必须注入这一层。
 */
object LogcatLogger : AgentLogger {
    override fun d(tag: String, message: String) { Log.d(tag, message) }
    override fun w(tag: String, message: String) { Log.w(tag, message) }
    override fun e(tag: String, message: String) { Log.e(tag, message) }
}
