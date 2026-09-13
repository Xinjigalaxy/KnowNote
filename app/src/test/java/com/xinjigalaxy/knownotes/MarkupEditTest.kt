package com.xinjigalaxy.knownotes

import com.xinjigalaxy.knownotes.data.markup.InlineMarkup
import com.xinjigalaxy.knownotes.ui.markdown.MarkupColor
import com.xinjigalaxy.knownotes.ui.markdown.MarkupEdit
import com.xinjigalaxy.knownotes.ui.markdown.MarkupSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器选区格式化（v1.7.0）。
 *
 * 这些边界（选中文首/文末、空选区、已经包过一层、连标记一起选中）用手点界面很难穷举，
 * 但它们全是纯字符串运算 —— 放在这里一次测完，界面层就只管调。
 */
class MarkupEditTest {

    @Test
    fun boldWrapsSelectionAndKeepsItSelected() {
        val r = MarkupEdit.bold("哈啰世界", 2, 4)
        assertEquals("哈啰**世界**", r.text)
        // 新选区仍是「世界」，方便接着再点颜色 / 字号
        assertEquals(4, r.start)
        assertEquals(6, r.end)
    }

    @Test
    fun boldTogglesOffWhenMarkersAreAroundSelection() {
        val r = MarkupEdit.bold("a **b** c", 4, 5)
        assertEquals("a b c", r.text)
        assertEquals(2, r.start)
        assertEquals(3, r.end)
    }

    @Test
    fun boldTogglesOffWhenMarkersAreSelectedToo() {
        val r = MarkupEdit.bold("a **b** c", 2, 7)
        assertEquals("a b c", r.text)
        assertEquals("b", r.text.substring(r.start, r.end))
    }

    @Test
    fun collapsedSelectionInsertsEmptyPairWithCursorInside() {
        val r = MarkupEdit.bold("ab", 1, 1)
        assertEquals("a****b", r.text)
        assertEquals("光标应落在两个标记中间", 3, r.start)
        assertEquals(3, r.end)
    }

    @Test
    fun italicUsesSingleAsterisks() {
        val r = MarkupEdit.italic("重点", 0, 2)
        assertEquals("*重点*", r.text)
    }

    @Test
    fun colorWrapsPlainSelection() {
        val r = MarkupEdit.recolor("重点", 0, 2, MarkupColor.BLUE)
        assertEquals("<color=blue>重点</color>", r.text)
        assertEquals(12, r.start)
        assertEquals(14, r.end)
    }

    @Test
    fun changingColorReplacesInsteadOfNesting() {
        val r = MarkupEdit.recolor("<color=red>重点</color>", 0, 24, MarkupColor.GREEN)
        assertEquals("换色不该套娃", "<color=green>重点</color>", r.text)
    }

    @Test
    fun colorInsideALongerTextKeepsTheRestIntact() {
        val source = "前面<color=red>重点</color>后面"
        val start = source.indexOf("重点")
        val r = MarkupEdit.recolor(source, start, start + 2, MarkupColor.PURPLE)
        assertEquals("前面<color=purple>重点</color>后面", r.text)
    }

    @Test
    fun sizeUsesRelativeFactor() {
        val r = MarkupEdit.resize("要点", 0, 2, MarkupSize.LARGE)
        assertEquals("<size=1.25>要点</size>", r.text)
    }

    @Test
    fun selectionAtVeryStartAndVeryEndIsSafe() {
        val head = MarkupEdit.bold("abc", 0, 1)
        assertEquals("**a**bc", head.text)
        val tail = MarkupEdit.bold("abc", 2, 3)
        assertEquals("ab**c**", tail.text)
    }

    @Test
    fun outOfRangeSelectionIsClamped() {
        val r = MarkupEdit.bold("ab", 5, 9)
        assertEquals("ab****", r.text)
    }

    @Test
    fun hasMarkupDetectsBothShapes() {
        assertTrue(MarkupEdit.hasMarkup("a **b** c", 4, 5, "**", "**"))
        assertTrue(MarkupEdit.hasMarkup("a **b** c", 2, 7, "**", "**"))
        assertFalse(MarkupEdit.hasMarkup("a b c", 2, 3, "**", "**"))
    }

    // ---------- 摘要只显示可读文字（InlineMarkup.plain）----------

    @Test
    fun plainStripsInlineMarkup() {
        assertEquals("红色重点", InlineMarkup.plain("<color=red>红色重点</color>"))
        assertEquals("放大", InlineMarkup.plain("<size=1.5>放大</size>"))
    }

    @Test
    fun plainHandlesNestingAndKeepsReadableText() {
        val source = "前面**<color=blue><size=1.5>标记</size></color>**后面"
        // 自定义标记去掉，标准 Markdown 记号保留（列表摘要一直是这样显示的）
        assertEquals("前面**标记**后面", InlineMarkup.plain(source))
    }

    @Test
    fun plainDropsAnyTagShapedTokenButKeepsPlainText() {
        // 渲染器对不认识的标记会原样显示；摘要则一律清掉（职责是「能读」，正文本身没动过）
        assertEquals("x", InlineMarkup.plain("<color=pink>x</color>"))
        assertEquals("普通一行文字", InlineMarkup.plain("普通一行文字"))
        assertEquals("没有尖括号的文本原样返回", "a < b", InlineMarkup.plain("a < b"))
    }

    @Test
    fun plainCleansUpHalfDeletedTags() {
        // 用户手改时只删了一半：落单的标记同样不该出现在摘要里
        assertEquals("红", InlineMarkup.plain("<color=red>红"))
        assertEquals("红", InlineMarkup.plain("红</color>"))
    }

    @Test
    fun summaryAlsoDropsMarkdownInlineMarkers() {
        val source = "演示：**标记**、*斜体*、~~删掉~~、`代码` 与 __加粗__ 照旧。"
        assertEquals("演示：标记、斜体、删掉、代码 与 加粗 照旧。", InlineMarkup.summary(source))
    }

    @Test
    fun summaryHandlesTagsAndMarkersTogether() {
        val source = "**<color=blue><size=1.5>标记</size></color>**"
        assertEquals("标记", InlineMarkup.summary(source))
    }

    @Test
    fun summaryDoesNotDamageOrdinaryText() {
        // 算式里的星号、文件名里的下划线都不是"斜体"，不能被吃掉
        assertEquals("2*3*4 = 24", InlineMarkup.summary("2*3*4 = 24"))
        assertEquals("my_file_name.txt", InlineMarkup.summary("my_file_name.txt"))
    }

    @Test
    fun plainKeepsMarkdownMarkersForIndexing() {
        // 索引那一档只去自定义标记：`**粗体**` 经分词后本来就是粗体这个词，不必额外处理
        assertEquals("**粗体**", InlineMarkup.plain("**粗体**"))
    }

    @Test
    fun imageReferenceIsHandledInSummaryAndIndex() {
        val source = "看这张：![架构图](img:img_abc123.jpg) 就这样。"
        // 摘要里显示占位，不显示文件名
        assertEquals("看这张：[架构图] 就这样。", InlineMarkup.summary(source))
        // 索引里保留说明文字（能搜到），但不索引文件名
        assertEquals("看这张：架构图 就这样。", InlineMarkup.plain(source))
    }

    @Test
    fun imageWithoutAltFallsBackToPlaceholder() {
        assertEquals("[图片]", InlineMarkup.summary("![](img:x.jpg)"))
    }
}
