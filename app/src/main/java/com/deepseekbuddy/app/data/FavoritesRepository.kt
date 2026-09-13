package com.deepseekbuddy.app.data

import com.deepseekbuddy.app.data.local.AppDao
import com.deepseekbuddy.app.data.local.FavoriteEntity
import kotlinx.coroutines.flow.Flow

/** 收藏仓库（多选消息收藏） */
class FavoritesRepository(private val dao: AppDao) {

    fun observeFavorites(): Flow<List<FavoriteEntity>> = dao.observeFavorites()

    suspend fun add(content: String, senderName: String? = null) {
        dao.insertFavorite(FavoriteEntity(content = content.trim(), senderName = senderName))
    }

    suspend fun delete(favorite: FavoriteEntity) = dao.deleteFavorite(favorite)
}
