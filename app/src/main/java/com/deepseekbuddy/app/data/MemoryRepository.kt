package com.deepseekbuddy.app.data

import com.deepseekbuddy.app.data.local.AppDao
import com.deepseekbuddy.app.data.local.MemoryEntity
import kotlinx.coroutines.flow.Flow

/** 长期记忆仓库（按角色隔离：每个角色只记得自己被告知的事） */
class MemoryRepository(private val dao: AppDao) {

    fun observeMemories(): Flow<List<MemoryEntity>> = dao.observeMemories()

    suspend fun add(personaId: Long, content: String, category: String = "事实", importance: Int = 3) {
        dao.insertMemory(
            MemoryEntity(
                personaId = personaId,
                content = content.trim(),
                category = category,
                importance = importance,
            )
        )
    }

    suspend fun delete(memory: MemoryEntity) = dao.deleteMemory(memory)

    /** 指定角色的全部记忆内容（自动抽取去重用） */
    suspend fun contentsByPersona(personaId: Long): List<String> = dao.memoryContentsByPersona(personaId)

    suspend fun setPinned(id: Long, pinned: Boolean) = dao.setMemoryPinned(id, pinned)

    suspend fun clearAll() = dao.clearMemories()

    suspend fun deleteByPersona(personaId: Long) = dao.deleteMemoriesByPersona(personaId)

    /** 注入用：指定角色的记忆，pinned 优先 → importance → 最近使用 */
    suspend fun forInjection(personaId: Long, limit: Int = 20): List<MemoryEntity> =
        dao.memoriesForInjection(personaId, limit)

    suspend fun touch(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.touchMemories(ids, System.currentTimeMillis())
    }
}
