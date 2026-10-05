package com.xinjigalaxy.knownotes.data.prefs

import android.content.Context
import com.xinjigalaxy.knownotes.data.settings.FontScale
import com.xinjigalaxy.knownotes.data.settings.TextColorOption
import com.xinjigalaxy.knownotes.data.settings.READ_FONT_DEFAULT
import com.xinjigalaxy.knownotes.data.settings.ThemeMode
import com.xinjigalaxy.knownotes.data.sync.SYNC_DEFAULT_PORT

/**
 * 界面状态的本地记忆（需求文档 3.4「常用筛选条件记忆」）。
 *
 * 放 SharedPreferences 而不是数据库：这是纯粹的界面状态，
 * 不需要参与同步、导出，也不值得为它污染业务表。
 * 约定：返回 null 表示"从未保存过"，由调用方决定默认值，避免这里的常量耦合到 UI。
 */
class UiPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("knownotes_ui", Context.MODE_PRIVATE)

    fun groupFilterOrNull(): Long? =
        if (prefs.contains(KEY_GROUP_FILTER)) prefs.getLong(KEY_GROUP_FILTER, 0L) else null

    fun saveGroupFilter(value: Long) {
        prefs.edit().putLong(KEY_GROUP_FILTER, value).apply()
    }

    fun tagFilterOrNull(): Set<Long>? =
        prefs.getStringSet(KEY_TAG_FILTER, null)
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()

    fun saveTagFilter(ids: Set<Long>) {
        prefs.edit().putStringSet(KEY_TAG_FILTER, ids.map { it.toString() }.toSet()).apply()
    }

    /** 列表展示形态：LIST / STAGGERED（存枚举名，便于以后加形态）。 */
    fun noteLayoutOrNull(): String? = prefs.getString(KEY_LAYOUT, null)

    fun saveNoteLayout(name: String) {
        prefs.edit().putString(KEY_LAYOUT, name).apply()
    }

    fun clearFilters() {
        prefs.edit().remove(KEY_GROUP_FILTER).remove(KEY_TAG_FILTER).apply()
    }

    // ---------- 局域网同步（需求文档 4） ----------

    /** 共享密钥。空串表示还没设过，由同步页生成一个可读的随机串。 */
    fun syncKey(): String = prefs.getString(KEY_SYNC_KEY, "").orEmpty()

    fun saveSyncKey(value: String) {
        prefs.edit().putString(KEY_SYNC_KEY, value).apply()
    }

    fun peerAddress(): String = prefs.getString(KEY_PEER, "").orEmpty()

    fun savePeerAddress(value: String) {
        prefs.edit().putString(KEY_PEER, value).apply()
    }

    fun hostPort(): Int = prefs.getInt(KEY_HOST_PORT, SYNC_DEFAULT_PORT)

    fun saveHostPort(value: Int) {
        prefs.edit().putInt(KEY_HOST_PORT, value).apply()
    }

    /**
     * 是否在下次启动应用时自动恢复主机监听。
     *
     * 存的是「用户的选择」而不是「服务当前开着」—— 进程被杀之后不该在用户不知情时
     * 悄悄又开始监听局域网。
     */
    fun hostAutoStart(): Boolean = prefs.getBoolean(KEY_HOST_AUTOSTART, false)

    fun saveHostAutoStart(value: Boolean) {
        prefs.edit().putBoolean(KEY_HOST_AUTOSTART, value).apply()
    }

    // ---------- 外观与回收站清理（设置页，v1.4.0） ----------

    /** 主题模式：SYSTEM / LIGHT / DARK（空串表示还没设过）。 */
    fun themeMode(): String = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name).orEmpty()

    fun saveThemeMode(name: String) {
        prefs.edit().putString(KEY_THEME_MODE, name).apply()
    }

    /** 主题色是否用 Android 12+ 的动态取色（默认关：保住初音绿品牌色）。 */
    fun dynamicColor(): Boolean = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)

    fun saveDynamicColor(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
    }

    fun autoPurgeTrash(): Boolean = prefs.getBoolean(KEY_AUTO_PURGE, false)

    fun saveAutoPurgeTrash(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PURGE, value).apply()
    }

    /** 回收站保留天数（超过就被定时清理彻底删掉）。 */
    fun trashRetentionDays(): Int = prefs.getInt(KEY_PURGE_DAYS, DEFAULT_RETENTION_DAYS)

    fun saveTrashRetentionDays(value: Int) {
        prefs.edit().putInt(KEY_PURGE_DAYS, value).apply()
    }

    /** 上次清理回收站的时间与条数，设置页展示用。 */
    fun lastTrashPurgeAt(): Long = prefs.getLong(KEY_LAST_PURGE_AT, 0L)

    fun lastTrashPurgeCount(): Int = prefs.getInt(KEY_LAST_PURGE_COUNT, 0)

    fun saveLastTrashPurge(at: Long, count: Int) {
        prefs.edit().putLong(KEY_LAST_PURGE_AT, at).putInt(KEY_LAST_PURGE_COUNT, count).apply()
    }

    /** 应用内语言 tag（空串 = 跟随系统）。 */
    fun appLanguage(): String = prefs.getString(KEY_LANGUAGE, "").orEmpty()

    fun saveAppLanguage(tag: String) {
        prefs.edit().putString(KEY_LANGUAGE, tag).apply()
    }

    /** 阅读页字号倍率（只作用于阅读页）。 */
    fun readFontScale(): Float = prefs.getFloat(KEY_READ_FONT_SCALE, READ_FONT_DEFAULT)

    fun saveReadFontScale(value: Float) {
        prefs.edit().putFloat(KEY_READ_FONT_SCALE, value).apply()
    }

    /**
     * 某台对端「已有哪些图片」的缓存，key 用 host:port。
     *
     * 只当缓存用：丢了最坏结果是下次同步多传几轮（对端会跳过已有的）。所以放偏好里而不是建表，
     * 省一次数据库迁移；多台对端各存各的，互不干扰。
     */
    fun peerImages(peerKey: String): Set<String>? = prefs.getStringSet("sync_peer_images:$peerKey", null)

    fun savePeerImages(peerKey: String, names: Set<String>) {
        prefs.edit().putStringSet("sync_peer_images:$peerKey", names).apply()
    }

    /** 阅读页展示方式；没设置过返回 null 交给 ReadMode 取默认值。 */
    fun readModeOrNull(): String? = prefs.getString(KEY_READ_MODE, null)

    fun saveReadMode(name: String) {
        prefs.edit().putString(KEY_READ_MODE, name).apply()
    }

    /** 检索范围（title/content/tags 的组合）；没设置过返回 null 表示"默认全选"。 */
    fun searchScopesOrNull(): Set<String>? = prefs.getStringSet(KEY_SEARCH_SCOPE, null)

    fun saveSearchScopes(names: Set<String>) {
        prefs.edit().putStringSet(KEY_SEARCH_SCOPE, names).apply()
    }

    /** 阅读设置：字号与文字颜色。 */
    fun fontScale(): String = prefs.getString(KEY_FONT_SCALE, FontScale.NORMAL.name).orEmpty()

    fun saveFontScale(name: String) {
        prefs.edit().putString(KEY_FONT_SCALE, name).apply()
    }

    fun textColor(): String = prefs.getString(KEY_TEXT_COLOR, TextColorOption.THEME.name).orEmpty()

    fun saveTextColor(name: String) {
        prefs.edit().putString(KEY_TEXT_COLOR, name).apply()
    }

    // ---------- 定时自动同步（v1.10.0） ----------

    /**
     * 是否开启定时自动同步（设备侧）。
     *
     * 与 `hostAutoStart` 一样，存的是「用户的选择」而不是「任务当前在不在」——
     * 周期任务被系统清掉过之后，应用启动时按这个开关自愈补排。
     */
    fun autoSyncEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_SYNC, false)

    fun saveAutoSyncEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()
    }

    /** 自动同步间隔（分钟），下限由 WorkManager 决定（15 分钟）。 */
    fun autoSyncIntervalMinutes(): Int = prefs.getInt(KEY_AUTO_SYNC_MINUTES, DEFAULT_AUTO_SYNC_MINUTES)

    fun saveAutoSyncIntervalMinutes(value: Int) {
        prefs.edit().putInt(KEY_AUTO_SYNC_MINUTES, value).apply()
    }

    fun autoSyncLastAt(): Long = prefs.getLong(KEY_AUTO_SYNC_LAST_AT, 0L)

    fun autoSyncLastOk(): Boolean = prefs.getBoolean(KEY_AUTO_SYNC_LAST_OK, false)

    /** 上次自动同步的结果摘要（成功是计数，失败是原因；技术文案，与同步日志同一风格）。 */
    fun autoSyncLastMessage(): String = prefs.getString(KEY_AUTO_SYNC_LAST_MESSAGE, "").orEmpty()

    fun saveAutoSyncResult(at: Long, ok: Boolean, message: String) {
        prefs.edit()
            .putLong(KEY_AUTO_SYNC_LAST_AT, at)
            .putBoolean(KEY_AUTO_SYNC_LAST_OK, ok)
            .putString(KEY_AUTO_SYNC_LAST_MESSAGE, message)
            .apply()
    }

    private companion object {
        const val KEY_GROUP_FILTER = "filter_group"
        const val KEY_TAG_FILTER = "filter_tags"
        const val KEY_LAYOUT = "note_layout"
        const val KEY_SYNC_KEY = "sync_key"
        const val KEY_PEER = "sync_peer"
        const val KEY_HOST_PORT = "sync_host_port"
        const val KEY_HOST_AUTOSTART = "sync_host_autostart"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_AUTO_PURGE = "trash_auto_purge"
        const val KEY_PURGE_DAYS = "trash_retention_days"
        const val KEY_LAST_PURGE_AT = "trash_last_purge_at"
        const val KEY_LAST_PURGE_COUNT = "trash_last_purge_count"
        const val KEY_LANGUAGE = "app_language"
        const val KEY_SEARCH_SCOPE = "search_scope"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_READ_FONT_SCALE = "read_font_scale"
        const val KEY_READ_MODE = "read_mode"
        const val KEY_AUTO_SYNC = "sync_auto_enabled"
        const val KEY_AUTO_SYNC_MINUTES = "sync_auto_minutes"
        const val KEY_AUTO_SYNC_LAST_AT = "sync_auto_last_at"
        const val KEY_AUTO_SYNC_LAST_OK = "sync_auto_last_ok"
        const val KEY_AUTO_SYNC_LAST_MESSAGE = "sync_auto_last_message"
        const val DEFAULT_RETENTION_DAYS = 30
        const val DEFAULT_AUTO_SYNC_MINUTES = 30
    }
}
