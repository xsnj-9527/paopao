package com.deepseekbuddy.app.data

import com.deepseekbuddy.app.data.local.AppDao
import com.deepseekbuddy.app.data.local.DaySummaryEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** 日历日记仓库 */
class DiaryRepository(private val dao: AppDao) {

    fun observeAll(): Flow<List<DaySummaryEntity>> = dao.observeDaySummaries()

    suspend fun getByDate(date: String): DaySummaryEntity? = dao.getDaySummary(date)

    suspend fun upsert(date: String, content: String) {
        dao.upsertDaySummary(DaySummaryEntity(date = date, content = content))
    }

    suspend fun delete(summary: DaySummaryEntity) = dao.deleteDaySummary(summary)

    /** 最近 N 天日记（含今天），按日期升序 */
    suspend fun recentDays(days: Int): List<DaySummaryEntity> {
        val start = LocalDate.now().minusDays((days - 1).toLong()).toString()
        return dao.daySummariesFrom(start)
    }
}
