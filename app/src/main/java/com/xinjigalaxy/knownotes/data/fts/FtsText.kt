package com.xinjigalaxy.knownotes.data.fts

import com.xinjigalaxy.knownotes.data.markup.InlineMarkup

import java.util.Locale

/**
 * FTS5 的分词辅助（需求文档 3.2 / 3.3）。
 *
 * 为什么需要它：SQLite 默认的 unicode61 分词器把连续汉字视为**一个 token**
 * （汉字属 Unicode Lo 字母类），于是「检索」无法命中「全文检索」——只有前缀匹配
 * 才有效，中文子串搜索基本失效。
 *
 * 这里的做法是：写入索引前把 CJK 逐字拆开（用空格分隔），拉丁词保持整词。
 * 查询时把连续 CJK 组成 phrase（`"检 索"`，位置相邻）+ 拉丁词用前缀（`sql*`），
 * 这样中文得到真正的**子串命中**，英文得到前缀命中，且完全不依赖自定义分词器。
 *
 * 索引里存的是分词后的副本，展示永远用 notes 主表的原文，因此不影响导出与阅读。
 */
object FtsText {

    private const val MAX_QUERY_CHARS = 128
    private const val MAX_GROUPS = 16
    const val MAX_RESULTS = 200

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || // CJK 统一表意
            code in 0x3400..0x4DBF || // 扩展 A
            code in 0xF900..0xFAFF || // 兼容表意
            code in 0x3040..0x30FF || // 平假名 / 片假名
            code in 0xAC00..0xD7AF // 谚文音节
    }

    private fun isWordChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    /** 写入 FTS 索引的文本：CJK 逐字空格分隔，拉丁词小写整词保留。 */
    fun index(text: String): String {
        if (text.isEmpty()) return ""
        // 行内标记不进索引：`color` / `size` 这些标签名不是笔记内容，
        // 搜到它们却没东西可高亮，比搜不到更让人困惑。
        val source = InlineMarkup.plain(text)
        if (source.isEmpty()) return ""
        val out = StringBuilder(source.length + 8)
        val word = StringBuilder()
        fun flushWord() {
            if (word.isNotEmpty()) {
                if (out.isNotEmpty()) out.append(' ')
                // Locale.ROOT：默认 locale 在土耳其语环境下会把 "I" 折成 "ı"，
                // 索引与查询若各折一次就可能对不上 —— 大小写无关必须是确定性的。
                out.append(word.toString().lowercase(Locale.ROOT))
                word.setLength(0)
            }
        }

        for (ch in source) {
            when {
                isCjk(ch) -> {
                    flushWord()
                    if (out.isNotEmpty()) out.append(' ')
                    out.append(ch)
                }

                isWordChar(ch) -> word.append(ch)
                else -> flushWord()
            }
        }
        flushWord()
        return out.toString()
    }

    /** 把用户输入编译成 FTS5 的 MATCH 表达式；无有效词元时返回 null。 */
    fun buildMatch(input: String): String? {
        val text = input.trim().take(MAX_QUERY_CHARS)
        if (text.isEmpty()) return null

        val groups = ArrayList<String>(MAX_GROUPS)
        val cjkRun = StringBuilder()
        val word = StringBuilder()

        fun flushCjk() {
            if (cjkRun.isEmpty()) return
            if (groups.size < MAX_GROUPS) {
                // 逐字 token 之间用空格 => phrase 查询，等价于子串匹配
                val phrase = cjkRun.toString().toCharArray().joinToString(" ")
                groups += "\"$phrase\""
            }
            cjkRun.setLength(0)
        }

        fun flushWord() {
            if (word.isEmpty()) return
            if (groups.size < MAX_GROUPS) groups += "${word.toString().lowercase(Locale.ROOT)}*"
            word.setLength(0)
        }

        for (ch in text) {
            when {
                isCjk(ch) -> {
                    flushWord()
                    cjkRun.append(ch)
                }

                isWordChar(ch) -> {
                    flushCjk()
                    word.append(ch)
                }

                else -> {
                    flushCjk()
                    flushWord()
                }
            }
        }
        flushCjk()
        flushWord()

        // 用空格连接 = 隐式 AND。不能写显式 "AND"：FTS4 的标准查询语法
        // （未编译 SQLITE_ENABLE_FTS3_PARENTHESIS 时）会把 AND 当成普通词元，
        // 导致整条查询永远搜不到东西。FTS5 同样支持空格隐式 AND。
        return if (groups.isEmpty()) null else groups.joinToString(" ")
    }

    /**
     * 某个词元是否出现在文本里。
     *
     * 刻意的两点：
     * - **不区分大小写**：索引侧与查询侧都走 lowercase，这里也用 ignoreCase 对齐，
     *   免得出现「索引里是小写、你搜大写就搜不到」这种半截子的大小写无关。
     * - 用子串判断而不是前缀：CJK 本来就是子串语义，拉丁词在索引侧是前缀匹配，
     *   子串是前缀的超集，用来判断"命中落在哪个字段"足够，也不会漏。
     */
    fun textHits(text: String, term: String): Boolean =
        term.isNotEmpty() && text.contains(term, ignoreCase = true)

    /** 一组词元能否在给定字段里全部找到（每个词元至少命中一个字段）。 */
    fun allTermsHit(fields: List<String>, terms: List<String>): Boolean =
        terms.all { term -> fields.any { textHits(it, term) } }

    /** 结果高亮用：CJK 连续串整体高亮，拉丁词整词高亮。 */
    fun highlightTerms(input: String): List<String> {
        val text = input.trim().take(MAX_QUERY_CHARS)
        if (text.isEmpty()) return emptyList()

        val terms = ArrayList<String>()
        val cjkRun = StringBuilder()
        val word = StringBuilder()

        fun flushCjk() {
            if (cjkRun.isNotEmpty()) terms += cjkRun.toString()
            cjkRun.setLength(0)
        }

        fun flushWord() {
            if (word.isNotEmpty()) terms += word.toString()
            word.setLength(0)
        }

        for (ch in text) {
            when {
                isCjk(ch) -> {
                    flushWord()
                    cjkRun.append(ch)
                }

                isWordChar(ch) -> {
                    flushCjk()
                    word.append(ch)
                }

                else -> {
                    flushCjk()
                    flushWord()
                }
            }
        }
        flushCjk()
        flushWord()
        return terms.distinct().sortedByDescending { it.length }
    }
}
