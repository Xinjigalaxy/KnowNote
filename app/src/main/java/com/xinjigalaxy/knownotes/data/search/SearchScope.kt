package com.xinjigalaxy.knownotes.data.search

import com.xinjigalaxy.knownotes.data.fts.FtsText
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import androidx.annotation.StringRes
import com.xinjigalaxy.knownotes.R

/**
 * 检索范围（v1.6.0）：标题 / 正文 / 标签，可任意组合，默认全选。
 *
 * 为什么不用 FTS 的**列限定**（`title:词`）：那是 SQLite 的"增强查询语法"，
 * 需要编译 `SQLITE_ENABLE_FTS3_PARENTHESIS` —— Android 自带的 SQLite 没有编，
 * 和当初 `AND` 失效是同一个原因。真机上引擎是 FTS4，走列限定会直接搜不到东西。
 *
 * 所以范围筛选做成**结果侧过滤**：候选来自 FTS（或 LIKE 降级），
 * 再按"每个词元至少命中一个被选中的字段"逐个筛。好处是两条检索路径语义完全一致，
 * 而且不碰 SQL 语法。代价是候选集要先放宽 limit（见 NoteListViewModel 的 candidateLimit）。
 */
enum class SearchScope(val prefName: String) {
    TITLE("title"),
    CONTENT("content"),
    TAGS("tags"),
    ;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            TITLE -> R.string.scope_title
            CONTENT -> R.string.scope_content
            TAGS -> R.string.scope_tags
        }

    companion object {
        /** 默认：三个都选。 */
        val ALL: Set<SearchScope> = entries.toSet()

        fun fromPrefs(names: Set<String>?): Set<SearchScope> {
            if (names == null) return ALL
            val picked = entries.filter { it.prefName in names }.toSet()
            // 空集合没有意义（会一条都搜不到），当成"没设置过"回到默认
            return picked.ifEmpty { ALL }
        }

        fun toPrefs(scopes: Set<SearchScope>): Set<String> =
            scopes.map { it.prefName }.toSet()

        /**
         * 候选集上限：范围一收窄，"命中的笔记数"可能远超默认上限，
         * 先多取一些再由结果侧过滤收口，免得过滤后只剩个位数。
         */
        fun candidateLimit(scopes: Set<SearchScope>): Int =
            if (scopes.size == entries.size) FtsText.MAX_RESULTS else FtsText.MAX_RESULTS * 4

        /** 取该笔记里被选中的字段文本（标签取名字）。 */
        fun fieldsOf(note: NoteWithTags, scopes: Set<SearchScope>): List<String> = buildList {
            if (TITLE in scopes) add(note.note.title)
            if (CONTENT in scopes) add(note.note.content)
            if (TAGS in scopes) note.tags.forEach { add(it.name) }
        }

        /**
         * 该笔记是否命中：**每个**查询词元都要能在选中字段里找到（大小写不敏感）。
         * 多词时允许分散在不同字段里 —— 「标题里有 Android、正文里有 gradle」
         * 在"标题+正文"范围内应当算命中。
         */
        fun matches(note: NoteWithTags, terms: List<String>, scopes: Set<SearchScope>): Boolean {
            if (terms.isEmpty() || scopes.isEmpty()) return true
            return FtsText.allTermsHit(fieldsOf(note, scopes), terms)
        }
    }
}
