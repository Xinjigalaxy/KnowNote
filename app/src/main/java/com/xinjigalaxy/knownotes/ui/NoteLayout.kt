package com.xinjigalaxy.knownotes.ui

/**
 * 列表展示形态，笔记页 / 分组页 / 标签页共用同一份选择（存在 UiPrefs 里）。
 */
enum class NoteLayout {
    LIST,
    STAGGERED,
    ;

    fun next(): NoteLayout = if (this == LIST) STAGGERED else LIST
}

internal fun String?.toNoteLayout(): NoteLayout =
    NoteLayout.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: NoteLayout.LIST
