package com.xinjigalaxy.knownotes.data.prefs

import android.content.Context
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

    private companion object {
        const val KEY_GROUP_FILTER = "filter_group"
        const val KEY_TAG_FILTER = "filter_tags"
        const val KEY_LAYOUT = "note_layout"
        const val KEY_SYNC_KEY = "sync_key"
        const val KEY_PEER = "sync_peer"
        const val KEY_HOST_PORT = "sync_host_port"
        const val KEY_HOST_AUTOSTART = "sync_host_autostart"
    }
}
