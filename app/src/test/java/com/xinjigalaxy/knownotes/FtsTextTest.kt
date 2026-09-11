package com.xinjigalaxy.knownotes

import com.xinjigalaxy.knownotes.data.fts.FtsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分词与 MATCH 表达式构造的正确性（纯 JVM）。
 * 这套规则是中文子串检索能生效的前提，错一点检索就悄悄失效。
 */
class FtsTextTest {

    @Test
    fun cjkIndexedPerCharacter() {
        assertEquals("全 文 检 索", FtsText.index("全文检索"))
        assertEquals("索 引 与 事 务", FtsText.index("索引与事务"))
    }

    @Test
    fun latinIndexedAsWholeWordLowercased() {
        assertEquals("sqlite fts5", FtsText.index("SQLite FTS5"))
        assertEquals("room 的 dao", FtsText.index("Room 的 DAO"))
    }

    @Test
    fun punctuationAndMarkupBecomesSeparator() {
        assertEquals("a b", FtsText.index("a, b!"))
        assertEquals("select from", FtsText.index("SELECT * FROM"))
    }

    @Test
    fun cjkQueryBecomesPhraseSoSubstringMatches() {
        assertEquals("\"检 索\"", FtsText.buildMatch("检索"))
        assertEquals("\"索\"", FtsText.buildMatch("索"))
    }

    @Test
    fun latinQueryBecomesPrefixMatch() {
        assertEquals("sql*", FtsText.buildMatch("SQL"))
        assertEquals("sqlite*", FtsText.buildMatch("sqlite"))
    }

    @Test
    fun mixedQueryCombinesGroupsWithImplicitAnd() {
        // 空格即隐式 AND（FTS4 与 FTS5 都支持；显式 AND 在 FTS4 上会被当成普通词）
        assertEquals("fts5* \"检 索\"", FtsText.buildMatch("FTS5 检索"))
    }

    @Test
    fun blankQueryProducesNothing() {
        assertNull(FtsText.buildMatch(""))
        assertNull(FtsText.buildMatch("   "))
        assertNull(FtsText.buildMatch("!!! ,,,"))
    }

    @Test
    fun highlightTermsKeepOriginalShape() {
        val terms = FtsText.highlightTerms("FTS5 检索")
        assertTrue(terms.contains("FTS5"))
        assertTrue(terms.contains("检索"))
        // 长度降序：长词先上色，避免短词把长词覆盖成两段
        assertEquals(listOf("FTS5", "检索"), terms)
    }
}
