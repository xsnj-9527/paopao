package com.deepseekbuddy.app.data

import com.deepseekbuddy.app.data.local.AppDao
import com.deepseekbuddy.app.data.local.NoteEntity
import kotlinx.coroutines.flow.Flow

/** 便签仓库（全局） */
class NotesRepository(private val dao: AppDao) {

    fun observeNotes(): Flow<List<NoteEntity>> = dao.observeNotes()

    suspend fun add(title: String, content: String) {
        val now = System.currentTimeMillis()
        dao.insertNote(NoteEntity(title = title.trim(), content = content.trim(), createdAt = now, updatedAt = now))
    }

    suspend fun delete(note: NoteEntity) = dao.deleteNote(note)
}
