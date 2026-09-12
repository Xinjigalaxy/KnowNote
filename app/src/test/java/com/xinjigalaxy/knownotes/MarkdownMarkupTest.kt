package com.xinjigalaxy.knownotes

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.xinjigalaxy.knownotes.ui.markdown.MarkupColor
import com.xinjigalaxy.knownotes.ui.markdown.MarkupSize
import com.xinjigalaxy.knownotes.ui.markdown.renderMarkdownForTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内标记的渲染解析（v1.7.0）。
 *
 * 编辑器能把 `<color>` / `<size>` 写进正文，读的时候必须渲染出来、且**不能吃掉文字**。
 * 这层是纯字符串处理，留了 renderMarkdownForTest 这个不依赖 Compose 运行时的入口，
 * 于是"嵌套"「不认识的标记要原样显示」这类边界可以在这里钉死。
 */
class MarkdownMarkupTest {

    @Test
    fun colorTagIsRenderedAsASpanAndTheTagItselfDisappears() {
        val annotated = renderMarkdownForTest("<color=red>重点</color>")
        assertEquals("重点", annotated.text)
        val colors = annotated.spanStyles.map { it.item.color }
        assertTrue("应带上红色", colors.contains(MarkupColor.RED.color(darkTheme = false)))
        assertTrue("深色主题用另一套色值", MarkupColor.RED.color(true) != MarkupColor.RED.color(false))
    }

    @Test
    fun sizeTagBecomesRelativeFontSize() {
        val annotated = renderMarkdownForTest("<size=1.5>大一点</size>")
        assertEquals("大一点", annotated.text)
        val sizes = annotated.spanStyles.map { it.item.fontSize }
        assertTrue("字号应是相对倍率 1.5em（这样才能和阅读页滑块叠加）", sizes.contains(1.5f.em))
    }

    @Test
    fun boldInsideColorKeepsBothStyles() {
        val annotated = renderMarkdownForTest("<color=blue>**粗**</color>")
        assertEquals("粗", annotated.text)
        val styles = annotated.spanStyles.map { it.item }
        assertTrue("颜色要在", styles.any { it.color == MarkupColor.BLUE.color(false) })
        assertTrue("加粗也要在", styles.any { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun colorInsideBoldKeepsBothStylesAndHidesTags() {
        // 回归：曾经"粗体在外、颜色在内"会把 <color=...> 当普通文字吐出来 ——
        // 根因是粗体分支只 append 原文、没有递归解析内容
        val annotated = renderMarkdownForTest("**<color=blue>标记</color>**")
        assertEquals("标记", annotated.text)
        val styles = annotated.spanStyles.map { it.item }
        assertTrue(styles.any { it.color == MarkupColor.BLUE.color(false) })
        assertTrue(styles.any { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun boldColorAndSizeCanStackThreeDeep() {
        val annotated = renderMarkdownForTest("**<color=blue><size=1.5>标记</size></color>**")
        assertEquals("标记", annotated.text)
        val styles = annotated.spanStyles.map { it.item }
        assertTrue(styles.any { it.color == MarkupColor.BLUE.color(false) })
        assertTrue(styles.any { it.fontWeight == FontWeight.Bold })
        assertTrue(styles.any { it.fontSize == 1.5f.em })
    }

    @Test
    fun colorInsideSizeKeepsBothStyles() {
        val annotated = renderMarkdownForTest("<size=0.85><color=purple>小紫</color></size>")
        assertEquals("小紫", annotated.text)
        val styles = annotated.spanStyles.map { it.item }
        assertTrue(styles.any { it.color == MarkupColor.PURPLE.color(false) })
        assertTrue(styles.any { it.fontSize == 0.85f.em })
    }

    @Test
    fun unknownTagIsLeftAsPlainText() {
        // 手打的 <color=pink> 不该被吃掉，也不该猜一个颜色给它
        val annotated = renderMarkdownForTest("<color=pink>x</color>")
        assertEquals("<color=pink>x</color>", annotated.text)
    }

    @Test
    fun markupInsideCodeBlockStaysLiteral() {
        val annotated = renderMarkdownForTest("```\n<color=red>x</color>\n```")
        assertTrue("代码块里不该解析标记", annotated.text.contains("<color=red>x</color>"))
    }

    @Test
    fun everyColorAndSizeTagRoundTrips() {
        MarkupColor.entries.forEach { color ->
            val text = "<color=${color.tag}>色</color>"
            assertEquals(color.tag, "色", renderMarkdownForTest(text).text)
        }
        MarkupSize.entries.forEach { size ->
            val text = "<size=${size.tag}>字</size>"
            assertEquals(size.tag, "字", renderMarkdownForTest(text).text)
        }
    }

    @Test
    fun plainTextIsUnaffected() {
        val annotated = renderMarkdownForTest("没有标记的一行普通文字")
        assertEquals("没有标记的一行普通文字", annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }
}
