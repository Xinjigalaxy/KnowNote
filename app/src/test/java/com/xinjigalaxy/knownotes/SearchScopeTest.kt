package com.xinjigalaxy.knownotes

import com.xinjigalaxy.knownotes.data.fts.FtsText
import com.xinjigalaxy.knownotes.data.model.Note
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.data.model.Tag
import com.xinjigalaxy.knownotes.data.search.SearchScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索范围 + 大小写无关的纯逻辑（v1.6.0）。
 *
 * 为什么值得单测：范围筛选是**结果侧**做的（Android 的 FTS4 没有"列限定"语法可用，
 * 见 SearchScope 的注释），也就是"能不能搜到"由 FTS 决定、"算不算命中该范围"由这里的判定决定。
 * 判定写错的表现是"明明搜到了却不出结果"，而且 FTS 与兜底两条路径会一起错。
 */
class SearchScopeTest {

    private fun note(
        title: String = "",
        content: String = "",
        tags: List<String> = emptyList(),
    ) = NoteWithTags(
        note = Note(id = 1L, title = title, content = content),
        tags = tags.mapIndexed { index, name -> Tag(id = index + 1L, name = name) },
    )

    private fun matches(note: NoteWithTags, query: String, vararg scopes: SearchScope) =
        SearchScope.matches(note, FtsText.highlightTerms(query), scopes.toSet())

    @Test
    fun scopeSeparatesTheThreeFields() {
        val n = note(
            title = "Kotlin 协程",
            content = "suspend 与结构化并发",
            tags = listOf("语言", "并发"),
        )

        // 只搜标题：命中标题里的词，正文 / 标签里的词不算
        assertTrue(matches(n, "协程", SearchScope.TITLE))
        assertFalse(matches(n, "suspend", SearchScope.TITLE))
        assertFalse(matches(n, "并发", SearchScope.TITLE))

        // 只搜正文
        assertTrue(matches(n, "结构化", SearchScope.CONTENT))
        assertFalse(matches(n, "不存在的词", SearchScope.CONTENT))
        assertFalse(matches(n, "Kotlin", SearchScope.CONTENT))

        // 只搜标签
        assertTrue(matches(n, "并发", SearchScope.TAGS))
        assertFalse(matches(n, "协程", SearchScope.TAGS))
    }

    @Test
    fun allScopesIsTheDefaultAndMatchesAnyField() {
        val n = note(title = "Kotlin 协程", content = "结构化并发", tags = listOf("语言"))
        assertTrue(matches(n, "协程", *SearchScope.ALL.toTypedArray()))
        assertTrue(matches(n, "结构化", *SearchScope.ALL.toTypedArray()))
        assertTrue(matches(n, "语言", *SearchScope.ALL.toTypedArray()))
    }

    @Test
    fun multipleTermsMayLandInDifferentSelectedFields() {
        val n = note(title = "Android 笔记", content = "gradle wrapper 的坑")

        // 两个词分别落在标题与正文：在"标题+正文"范围内算命中
        assertTrue(matches(n, "android gradle", SearchScope.TITLE, SearchScope.CONTENT))
        // 只选标题时，"gradle" 在正文里 → 不算命中
        assertFalse(matches(n, "android gradle", SearchScope.TITLE))
        // 但只含标题里的那个词仍然命中
        assertTrue(matches(n, "android", SearchScope.TITLE))
    }

    @Test
    fun matchingIgnoresCaseForAsciiAndAccents() {
        val n = note(title = "SQLite 全文检索", content = "Café 与 café", tags = listOf("FTS"))

        assertTrue(matches(n, "sqlite", SearchScope.TITLE))
        assertTrue(matches(n, "SQLITE", SearchScope.TITLE))
        assertTrue(matches(n, "fTs", SearchScope.TAGS))
        // contains(ignoreCase = true) 走 Unicode 规则：重音字母的大小写也折叠
        assertTrue(matches(n, "CAFÉ", SearchScope.CONTENT))
        assertTrue(matches(n, "café", SearchScope.CONTENT))
    }

    @Test
    fun emptyQueryOrNoScopeMatchesEverything() {
        val n = note(title = "任意", content = "任意")
        assertTrue(matches(n, "   ", SearchScope.TITLE))          // 空查询不过滤
        assertTrue(SearchScope.matches(n, listOf("随便"), emptySet())) // 空范围也不过滤
    }

    @Test
    fun tagNamesParticipateInTagScope() {
        val n = note(title = "标题", content = "正文", tags = listOf("安卓", "SQLite"))
        assertTrue(matches(n, "安卓", SearchScope.TAGS))
        assertTrue(matches(n, "sqlite", SearchScope.TAGS))
        assertFalse(matches(n, "标题", SearchScope.TAGS))
    }

    @Test
    fun scopeSelectionRoundTripsThroughPrefNames() {
        assertTrue(SearchScope.fromPrefs(null) == SearchScope.ALL)
        assertTrue(SearchScope.fromPrefs(emptySet()) == SearchScope.ALL)  // 空集合当成"没设置过"
        val picked = setOf(SearchScope.TITLE)
        assertTrue(SearchScope.fromPrefs(SearchScope.toPrefs(picked)) == picked)
    }

    @Test
    fun candidateLimitWidensOnlyWhenNarrowing() {
        assertTrue(SearchScope.candidateLimit(SearchScope.ALL) == FtsText.MAX_RESULTS)
        assertTrue(
            "范围收窄时要放宽候选集，否则过滤后可能只剩个位数",
            SearchScope.candidateLimit(setOf(SearchScope.TAGS)) > FtsText.MAX_RESULTS,
        )
    }
}
