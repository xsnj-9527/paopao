package com.deepseekbuddy.app.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    // ---- personas ----
    @Query("SELECT * FROM personas ORDER BY createdAt")
    fun observePersonas(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun getPersona(id: Long): PersonaEntity?

    @Query("SELECT * FROM personas WHERE id = :id")
    fun observePersona(id: Long): Flow<PersonaEntity?>

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun personaCount(): Int

    @Insert
    suspend fun insertPersona(persona: PersonaEntity): Long

    @Update
    suspend fun updatePersona(persona: PersonaEntity)

    @Delete
    suspend fun deletePersona(persona: PersonaEntity)

    // ---- conversations ----
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: Long): Flow<ConversationEntity?>

    @Query("UPDATE conversations SET bgRef = :bgRef WHERE id = :id")
    suspend fun updateConversationBg(id: Long, bgRef: String)

    @Query("UPDATE conversations SET summary = :summary WHERE id = :id")
    suspend fun updateConversationSummary(id: Long, summary: String)

    @Query("SELECT * FROM conversations WHERE personaId = :personaId")
    suspend fun getConversationByPersona(personaId: Long): ConversationEntity?

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET lastMessage = :last, updatedAt = :ts, title = :title WHERE id = :id")
    suspend fun updateConversationMeta(id: Long, last: String?, ts: Long, title: String)

    @Query("DELETE FROM conversations WHERE personaId = :personaId")
    suspend fun deleteConversationsByPersona(personaId: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    // ---- messages ----
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt")
    suspend fun messagesFor(convId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt")
    fun observeMessages(convId: Long): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET recalled = 1 WHERE id = :id")
    suspend fun markMessageRecalled(id: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    // ---- favorites（收藏）----
    @Query("SELECT * FROM favorites ORDER BY createdAt DESC")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Insert
    suspend fun insertFavorite(favorite: FavoriteEntity): Long

    @Delete
    suspend fun deleteFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun deleteMessagesByConversation(convId: Long)

    // ---- memories（长期记忆，按角色隔离）----
    @Query("SELECT * FROM memories WHERE personaId = :personaId ORDER BY pinned DESC, importance DESC, lastUsedAt DESC LIMIT :limit")
    suspend fun memoriesForInjection(personaId: Long, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY pinned DESC, createdAt DESC")
    fun observeMemories(): Flow<List<MemoryEntity>>

    @Query("UPDATE memories SET lastUsedAt = :ts WHERE id IN (:ids)")
    suspend fun touchMemories(ids: List<Long>, ts: Long)

    @Query("UPDATE memories SET pinned = :pinned WHERE id = :id")
    suspend fun setMemoryPinned(id: Long, pinned: Boolean)

    @Query("DELETE FROM memories")
    suspend fun clearMemories()

    @Query("DELETE FROM memories WHERE personaId = :personaId")
    suspend fun deleteMemoriesByPersona(personaId: Long)

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    // ---- notes（便签）----
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Insert
    suspend fun insertNote(note: NoteEntity): Long

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    // ---- day_summaries（日历日记）----
    @Query("SELECT * FROM day_summaries ORDER BY date DESC")
    fun observeDaySummaries(): Flow<List<DaySummaryEntity>>

    @Query("SELECT * FROM day_summaries WHERE date = :date")
    suspend fun getDaySummary(date: String): DaySummaryEntity?

    @Query("SELECT * FROM day_summaries WHERE date >= :startDate ORDER BY date")
    suspend fun daySummariesFrom(startDate: String): List<DaySummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDaySummary(summary: DaySummaryEntity)

    @Delete
    suspend fun deleteDaySummary(summary: DaySummaryEntity)

    // ---- 行为画像原料 ----
    @Query("SELECT content FROM messages WHERE role = 'user' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentUserMessages(limit: Int): List<String>

    @Query("SELECT createdAt FROM messages WHERE role = 'user' ORDER BY createdAt DESC LIMIT 500")
    suspend fun recentUserTimestamps(): List<Long>

    // ---- 记忆去重 ----
    @Query("SELECT content FROM memories WHERE personaId = :personaId")
    suspend fun memoryContentsByPersona(personaId: Long): List<String>

    // ---- 群聊参与者 ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParticipant(participant: ParticipantEntity)

    @Query("DELETE FROM conversation_participants WHERE conversationId = :convId")
    suspend fun deleteParticipantsByConversation(convId: Long)

    @Query("DELETE FROM conversation_participants WHERE personaId = :personaId")
    suspend fun deleteParticipantsByPersona(personaId: Long)

    @Query("SELECT * FROM conversation_participants WHERE conversationId = :convId")
    suspend fun participantsFor(convId: Long): List<ParticipantEntity>

    @Query(
        "SELECT cp.conversationId AS conversationId, p.* FROM conversation_participants cp " +
            "JOIN personas p ON p.id = cp.personaId"
    )
    fun observeParticipantsWithPersona(): Flow<List<ParticipantRow>>

    @Query(
        "SELECT cp.conversationId AS conversationId, p.* FROM conversation_participants cp " +
            "JOIN personas p ON p.id = cp.personaId WHERE cp.conversationId = :convId"
    )
    suspend fun participantsWithPersona(convId: Long): List<ParticipantRow>

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long
}

/** 群成员联查结果 */
data class ParticipantRow(
    @ColumnInfo(name = "conversationId")
    val conversationId: Long,
    @Embedded
    val persona: PersonaEntity,
)

@Database(
    entities = [
        PersonaEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        NoteEntity::class,
        DaySummaryEntity::class,
        ParticipantEntity::class,
        FavoriteEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v1 → v2：新增 memories 表 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `content` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `importance` INTEGER NOT NULL,
                        `pinned` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        `sourceConvId` INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        /** v2 → v3：记忆按角色隔离，memories 增加 personaId 列 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `personaId` INTEGER")
            }
        }

        /** v3 → v4：会话独立聊天背景 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `bgRef` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v4 → v5：便签表 + 会话滚动摘要列 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `summary` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5 → v6：日历日记表 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `day_summaries` (
                        `date` TEXT NOT NULL PRIMARY KEY,
                        `content` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** v6 → v7：群聊（isGroup 标记 + 发言者 + 参与者表） */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `isGroup` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `senderName` TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_participants` (
                        `conversationId` INTEGER NOT NULL,
                        `personaId` INTEGER NOT NULL,
                        PRIMARY KEY(`conversationId`, `personaId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** v7 → v8：消息撤回/引用字段 + 收藏表 */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `recalled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `quotedId` INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorites` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `content` TEXT NOT NULL,
                        `senderName` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "deepseek_buddy.db",
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
