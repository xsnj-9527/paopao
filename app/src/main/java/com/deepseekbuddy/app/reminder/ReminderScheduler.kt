package com.deepseekbuddy.app.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.deepseekbuddy.agent.ports.ReminderGateway
import com.deepseekbuddy.app.MainActivity
import com.deepseekbuddy.app.R

class ReminderScheduler(private val context: Context) : ReminderGateway {

    private val app = context.applicationContext

    /** 应用内通知路径：精确闹钟 + 通知（App 被杀/被冻结时不保证送达） */
    override fun schedule(triggerAtMillis: Long, title: String) {
        val intent = Intent(app, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_TITLE, title)
        val pi = PendingIntent.getBroadcast(
            app,
            title.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = app.getSystemService(AlarmManager::class.java)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            // 未授予精确闹钟权限时退回 inexact
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    companion object {
        const val CHANNEL_ID = "reminders"

        fun showNotification(context: Context, title: String) {
            val app = context.applicationContext
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "提醒", NotificationManager.IMPORTANCE_HIGH)
            )
            val contentIntent = PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("⏰ 提醒")
                .setContentText(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(title))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(title.hashCode(), notification)
        }
    }
}
