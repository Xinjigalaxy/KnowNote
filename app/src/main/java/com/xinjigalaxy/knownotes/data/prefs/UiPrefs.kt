package com.xinjigalaxy.knownotes.data.prefs

import android.content.Context

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

    /** 编辑页是否默认进预览模式：写代码知识的笔记大多希望直接看渲染效果。 */
    var preferMarkdownPreview: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PREVIEW, value).apply()
        }

    fun clearFilters() {
        prefs.edit().remove(KEY_GROUP_FILTER).remove(KEY_TAG_FILTER).apply()
    }

    private companion object {
        const val KEY_GROUP_FILTER = "filter_group"
        const val KEY_TAG_FILTER = "filter_tags"
        const val KEY_PREVIEW = "prefer_markdown_preview"
    }
}
