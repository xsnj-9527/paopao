package com.deepseekbuddy.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFormat {

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    /** 今天 HH:mm / 昨天 HH:mm / M月d日 */
    fun relative(ts: Long): String {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val time = Instant.ofEpochMilli(ts).atZone(zone).toLocalTime().format(timeFmt)
        val today = LocalDate.now()
        return when (date) {
            today -> time
            today.minusDays(1) -> "昨天 $time"
            else -> DateTimeFormatter.ofPattern("M月d日").format(date)
        }
    }
}
