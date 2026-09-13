package com.deepseekbuddy.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "提醒"
        ReminderScheduler.showNotification(context, title)
    }

    companion object {
        const val EXTRA_TITLE = "title"
    }
}
