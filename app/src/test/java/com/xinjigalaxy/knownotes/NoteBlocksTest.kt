package com.xinjigalaxy.knownotes

import com.xinjigalaxy.knownotes.ui.markdown.NoteBlock
import com.xinjigalaxy.knownotes.ui.markdown.splitNoteBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文分块（v1.8.0）：图片必须**独占整行**。
 *
 * 这是"图片是行内元素但占整行"这条要求的落点：不管图片引用写在行首、行中还是行尾，
 * 都要被抠出来单独成块 —— 否则渲染时会跟着文字换行，做不到整行。
 */
class NoteBlocksTest {

    private fun texts(blocks: List<NoteBlock>) = blocks.filterIsInstance<NoteBlock.Text>().map { it.markdown }
    private fun images(blocks: List<NoteBlock>) = blocks.filterIsInstance<NoteBlock.Image>()

    @Test
    fun plainTextIsOneBlock() {
        val blocks = splitNoteBlocks("只有文字的一行")
        assertEquals(1, blocks.size)
        assertEquals("只有文字的一行", texts(blocks).single())
    }

    @Test
    fun imageOnItsOwnLineBecomesItsOwnBlock() {
        val blocks = splitNoteBlocks("上一段\n\n![示例图](img:img_abc.jpg)\n\n下一段")
        assertEquals(3, blocks.size)
        assertEquals(listOf("上一段", "下一段"), texts(blocks))
        assertEquals("示例图", images(blocks).single().alt)
        assertEquals("img_abc.jpg", images(blocks).single().name)
    }

    @Test
    fun imageInsideTextStillGetsItsOwnLine() {
        // 图片写在文字中间：前后文字各自成段，图片仍然独占一块（渲染时独占整行）
        val blocks = splitNoteBlocks("前面的话 ![图](img:a.jpg) 后面的话")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is NoteBlock.Image)
        assertEquals(listOf("前面的话", "后面的话"), texts(blocks))
    }

    @Test
    fun consecutiveImagesBecomeSeparateBlocks() {
        val blocks = splitNoteBlocks("![一](img:1.jpg)\n![二](img:2.jpg)")
        assertEquals(2, blocks.size)
        assertEquals(listOf("1.jpg", "2.jpg"), images(blocks).map { it.name })
    }

    @Test
    fun emptyContentProducesNoBlocks() {
        assertTrue(splitNoteBlocks("").isEmpty())
        assertTrue(splitNoteBlocks("   \n  ").isEmpty())
    }

    @Test
    fun nonImageMarkdownStaysText() {
        // 普通链接 / 普通文本里的 img: 字样不该被当成图片
        val blocks = splitNoteBlocks("看 [链接](https://example.com) 和 img:notaref")
        assertEquals(1, blocks.size)
        assertTrue(texts(blocks).single().contains("img:notaref"))
    }

    @Test
    fun altTextMayBeEmpty() {
        val blocks = splitNoteBlocks("![](img:x.jpg)")
        assertEquals("", images(blocks).single().alt)
        assertEquals("x.jpg", images(blocks).single().name)
    }
}
