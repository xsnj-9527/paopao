package com.deepseekbuddy.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deepseekbuddy.app.agent.AgentConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 用户档案 */
data class UserProfile(
    val name: String = "",
    val job: String = "",
    val bio: String = "",
)

/**
 * 应用设置存储（DataStore）。
 * TODO(阶段2)：API Key 迁移到 Keystore 加密存储（EncryptedSharedPreferences）。
 */
class SettingsStore(private val context: Context) {

    val apiKey: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY] ?: "" }
    val model: Flow<String> = context.dataStore.data.map { it[KEY_MODEL] ?: DEFAULT_MODEL }
    val temperature: Flow<Float> = context.dataStore.data.map { it[KEY_TEMPERATURE] ?: DEFAULT_TEMPERATURE }
    val thinking: Flow<Boolean> = context.dataStore.data.map { it[KEY_THINKING] ?: false }
    /** 用户画像（首次向导填写）：JSON {gender, interests[], needs[]} */
    val userProfileJson: Flow<String> = context.dataStore.data.map { it[KEY_USER_PROFILE] ?: "" }
    /** 主题模式：system / light / dark */
    val themeMode: Flow<String> = context.dataStore.data.map { it[KEY_THEME_MODE] ?: "system" }
    /** 用户头像：emoji:🐱 或 file:/path */
    val userAvatar: Flow<String> = context.dataStore.data.map { it[KEY_USER_AVATAR] ?: "" }
    /** 显示思考过程（思考模式下展示推理内容，默认隐藏更自然） */
    val showThinking: Flow<Boolean> = context.dataStore.data.map { it[KEY_SHOW_THINKING] ?: false }
    /** 隐私模式：限制自动分析对话内容（阶段 4 自动抽取不运行，仅保留显式「记住」） */
    val privacyMode: Flow<Boolean> = context.dataStore.data.map { it[KEY_PRIVACY_MODE] ?: false }
    /** 时空胶囊开关（默认开；有日记数据才会推送） */
    val capsuleEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_CAPSULE_ENABLED] ?: true }
    /** 行为准则（行为画像分析产物，多行文本） */
    val behaviorRules: Flow<String> = context.dataStore.data.map { it[KEY_BEHAVIOR_RULES] ?: "" }
    /** 用户档案（我的-资料页编辑，角色可读取） */
    val userName: Flow<String> = context.dataStore.data.map { it[KEY_USER_NAME] ?: "" }
    val userJob: Flow<String> = context.dataStore.data.map { it[KEY_USER_JOB] ?: "" }
    val userBio: Flow<String> = context.dataStore.data.map { it[KEY_USER_BIO] ?: "" }

    suspend fun setApiKey(value: String) {
        context.dataStore.edit { it[KEY_API_KEY] = value.trim() }
    }

    suspend fun setModel(value: String) {
        context.dataStore.edit { it[KEY_MODEL] = value.trim().ifBlank { DEFAULT_MODEL } }
    }

    suspend fun setTemperature(value: Float) {
        context.dataStore.edit { it[KEY_TEMPERATURE] = value }
    }

    suspend fun setThinking(value: Boolean) {
        context.dataStore.edit { it[KEY_THINKING] = value }
    }

    suspend fun setUserProfileJson(json: String) {
        context.dataStore.edit { it[KEY_USER_PROFILE] = json }
    }

    suspend fun setThemeMode(value: String) {
        context.dataStore.edit { it[KEY_THEME_MODE] = value }
    }

    suspend fun setUserAvatar(value: String) {
        context.dataStore.edit { it[KEY_USER_AVATAR] = value }
    }

    suspend fun setShowThinking(value: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_THINKING] = value }
    }

    suspend fun setPrivacyMode(value: Boolean) {
        context.dataStore.edit { it[KEY_PRIVACY_MODE] = value }
    }

    suspend fun setCapsuleEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_CAPSULE_ENABLED] = value }
    }

    suspend fun setUserName(value: String) {
        context.dataStore.edit { it[KEY_USER_NAME] = value.trim() }
    }

    suspend fun setUserJob(value: String) {
        context.dataStore.edit { it[KEY_USER_JOB] = value.trim() }
    }

    suspend fun setUserBio(value: String) {
        context.dataStore.edit { it[KEY_USER_BIO] = value.trim() }
    }

    /** 用户档案（注入系统提示词用） */
    suspend fun userProfile(): UserProfile {
        val d = context.dataStore.data.first()
        return UserProfile(
            name = d[KEY_USER_NAME] ?: "",
            job = d[KEY_USER_JOB] ?: "",
            bio = d[KEY_USER_BIO] ?: "",
        )
    }

    suspend fun userAvatarValue(): String = context.dataStore.data.first()[KEY_USER_AVATAR] ?: ""

    // ---- 行为画像 / 时空胶囊 值读取与写入 ----

    suspend fun privacyModeValue(): Boolean = context.dataStore.data.first()[KEY_PRIVACY_MODE] ?: false

    suspend fun capsuleEnabledValue(): Boolean = context.dataStore.data.first()[KEY_CAPSULE_ENABLED] ?: true

    suspend fun behaviorRulesValue(): String = context.dataStore.data.first()[KEY_BEHAVIOR_RULES] ?: ""

    suspend fun setBehaviorRules(value: String) {
        context.dataStore.edit { it[KEY_BEHAVIOR_RULES] = value }
    }

    suspend fun lastAnalysisAtValue(): Long = context.dataStore.data.first()[KEY_ANALYSIS_AT] ?: 0L

    suspend fun setLastAnalysisAt(ts: Long) {
        context.dataStore.edit { it[KEY_ANALYSIS_AT] = ts }
    }

    /** 常聊时段：逗号分隔的小时列表，如 "9,21,22" */
    suspend fun activeHoursValue(): String = context.dataStore.data.first()[KEY_ACTIVE_HOURS] ?: ""

    suspend fun setActiveHours(value: String) {
        context.dataStore.edit { it[KEY_ACTIVE_HOURS] = value }
    }

    /** 今日时空胶囊已推送次数（跨天自动归零） */
    suspend fun capsuleCountToday(): Int {
        val d = context.dataStore.data.first()
        return if (d[KEY_CAPSULE_DATE] == java.time.LocalDate.now().toString()) d[KEY_CAPSULE_COUNT] ?: 0 else 0
    }

    suspend fun incrementCapsuleToday() {
        val today = java.time.LocalDate.now().toString()
        context.dataStore.edit {
            if (it[KEY_CAPSULE_DATE] != today) {
                it[KEY_CAPSULE_DATE] = today
                it[KEY_CAPSULE_COUNT] = 1
            } else {
                it[KEY_CAPSULE_COUNT] = (it[KEY_CAPSULE_COUNT] ?: 0) + 1
            }
        }
    }

    /** 读取当前完整配置（每次发送前调用，保证设置修改即时生效） */
    suspend fun currentConfig(): AgentConfig {
        val data = context.dataStore.data.first()
        return AgentConfig(
            apiKey = data[KEY_API_KEY] ?: "",
            model = data[KEY_MODEL] ?: DEFAULT_MODEL,
            temperature = (data[KEY_TEMPERATURE] ?: DEFAULT_TEMPERATURE).toDouble(),
            thinking = data[KEY_THINKING] ?: false,
        )
    }

    companion object {
        private const val DEFAULT_MODEL = "deepseek-v4-flash"
        private const val DEFAULT_TEMPERATURE = 1.2f

        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_MODEL = stringPreferencesKey("model")
        private val KEY_TEMPERATURE = floatPreferencesKey("temperature")
        private val KEY_THINKING = booleanPreferencesKey("thinking")
        private val KEY_USER_PROFILE = stringPreferencesKey("user_profile")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_USER_AVATAR = stringPreferencesKey("user_avatar")
        private val KEY_SHOW_THINKING = booleanPreferencesKey("show_thinking")
        private val KEY_PRIVACY_MODE = booleanPreferencesKey("privacy_mode")
        private val KEY_CAPSULE_ENABLED = booleanPreferencesKey("capsule_enabled")
        private val KEY_BEHAVIOR_RULES = stringPreferencesKey("behavior_rules")
        private val KEY_ANALYSIS_AT = longPreferencesKey("analysis_at")
        private val KEY_ACTIVE_HOURS = stringPreferencesKey("active_hours")
        private val KEY_CAPSULE_DATE = stringPreferencesKey("capsule_date")
        private val KEY_CAPSULE_COUNT = intPreferencesKey("capsule_count")
        private val KEY_USER_NAME = stringPreferencesKey("user_name")
        private val KEY_USER_JOB = stringPreferencesKey("user_job")
        private val KEY_USER_BIO = stringPreferencesKey("user_bio")
    }
}
