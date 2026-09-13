package com.deepseekbuddy.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deepseekbuddy.app.DeepSeekBuddyApp
import com.deepseekbuddy.app.MainActivity
import com.deepseekbuddy.app.R
import com.deepseekbuddy.app.agent.ChatMessage
import com.deepseekbuddy.app.agent.llm.DeepSeekClient
import com.deepseekbuddy.app.data.SettingsStore
import java.time.LocalTime

/**
 * 时空胶囊：在用户常聊时段，基于日历日记生成一句无压力关心。
 * 限制：每天最多 2 次；隐私模式/无日记/非活跃时段自动跳过。
 */
class CapsuleWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        val ctx = applicationContext
        val app = ctx as DeepSeekBuddyApp
        val settings = SettingsStore(ctx)

        if (!settings.capsuleEnabledValue()) return Result.success()
        if (settings.privacyModeValue()) return Result.success()
        if (settings.capsuleCountToday() >= MAX_PER_DAY) return Result.success()

        // 常聊时段检查
        val hour = LocalTime.now().hour
        val active = settings.activeHoursValue().split(",").mapNotNull { it.toIntOrNull() }
        if (active.isNotEmpty() && hour !in active) return Result.success()

        val diaries = app.container.diaries.recentDays(2)
        if (diaries.isEmpty()) return Result.success()

        val config = settings.currentConfig()
        if (config.apiKey.isBlank()) return Result.success()

        // 由日记生成一句关心（失败则取日记原文片段兜底）
        val text = runCatching {
            val client = DeepSeekClient(config.copy(thinking = false, temperature = 1.0))
            val sb = StringBuilder()
            client.chat(
                listOf(
                    ChatMessage("system", CAPSULE_PROMPT),
                    ChatMessage("user", diaries.joinToString("\n") { "${it.date}：${it.content}" }),
                ),
                emptyList(),
                onDelta = { sb.append(it) },
            )
            sb.toString().trim().ifBlank { null }
        }.getOrNull() ?: diaries.last().content.take(20)

        showNotification(ctx, text)
        settings.incrementCapsuleToday()
        Result.success()
    }.getOrElse { Result.success() }

    private fun showNotification(ctx: Context, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "时空胶囊", NotificationManager.IMPORTANCE_DEFAULT))

        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            ctx, 2,
            Intent(ctx, CapsuleDismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("💊 时空胶囊")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "聊聊", open)
            .addAction(0, "稍后再说", dismiss)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val WORK_NAME = "capsule"
        const val MAX_PER_DAY = 2
        const val CHANNEL_ID = "capsule"
        const val NOTIFICATION_ID = 1001
        private const val CAPSULE_PROMPT =
            "你是陪伴型 AI。根据用户最近几天的日记，生成一句 20 字以内的关心问候，口语化自然，像老朋友随口一问，" +
            "不要用「宝宝」等过度亲密的称呼，不要用 emoji。直接输出，不要解释。"
    }
}

/** 通知「稍后再说」：仅关闭通知 */
class CapsuleDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.getSystemService(NotificationManager::class.java).cancel(CapsuleWorker.NOTIFICATION_ID)
    }
}
