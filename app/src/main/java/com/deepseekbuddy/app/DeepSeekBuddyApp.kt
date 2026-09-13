package com.deepseekbuddy.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.deepseekbuddy.app.agent.context.BehaviorAnalyzer
import com.deepseekbuddy.agent.llm.DeepSeekClient
import com.deepseekbuddy.app.data.AppContainer
import com.deepseekbuddy.app.data.SettingsStore
import com.deepseekbuddy.app.reminder.ReminderScheduler
import com.deepseekbuddy.app.worker.CapsuleWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DeepSeekBuddyApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    ReminderScheduler.CHANNEL_ID,
                    getString(R.string.notification_channel_reminders),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }

        // 行为画像：App 退后台时轻量分析（24h 节流；隐私模式跳过）
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_STOP) {
                    appScope.launch {
                        BehaviorAnalyzer(
                            dao = container.dao,
                            settings = SettingsStore(this@DeepSeekBuddyApp),
                            clientFactory = { DeepSeekClient(it) },
                        ).analyzeIfDue()
                    }
                }
            }
        })

        // 时空胶囊：每 15 分钟检查推送条件
        val capsuleRequest = PeriodicWorkRequestBuilder<CapsuleWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(CapsuleWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, capsuleRequest)
    }
}
