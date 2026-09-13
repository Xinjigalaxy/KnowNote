package com.xinjigalaxy.knownotes.ui.markdown

import com.xinjigalaxy.knownotes.data.media.NoteImages

/** 正文的一个块：可渲染的文字段，或一张独占整行的图片。 */
sealed interface NoteBlock {
    data class Text(val markdown: String) : NoteBlock
    data class Image(val alt: String, val name: String) : NoteBlock
}

/** `![说明](img:文件名)` —— 标准 Markdown 图片语法，路径用应用自己的 scheme。 */
private val IMAGE = Regex("!\\[([^\\]]*)\\]\\(${Regex.escape(NoteImages.SCHEME)}([^)\\s]+)\\)")

/**
 * 把正文切成"文字段 / 图片块"。
 *
 * 图片要求**独占整行**，所以不管它写在行首、行中还是行尾，都抠出来单独成块：
 * 渲染时它总是一整行、铺满宽度，而文字段继续走 Markdown 渲染。
 * 这也是为什么用块切分而不是 AnnotatedString 的行内图片 —— 行内图片会跟着文字换行，
 * 做不到"永远自己一行"。
 */
fun splitNoteBlocks(content: String): List<NoteBlock> {
    if (content.isEmpty()) return emptyList()
    val blocks = mutableListOf<NoteBlock>()
    var cursor = 0
    IMAGE.findAll(content).forEach { match ->
        val text = content.substring(cursor, match.range.first)
        // 图片两侧的文字各自成段（图片独占整行，本来就会换行），顺手去掉边界空白
        if (text.isNotBlank()) blocks += NoteBlock.Text(text.trim())
        blocks += NoteBlock.Image(alt = match.groupValues[1], name = match.groupValues[2])
        cursor = match.range.last + 1
    }
    val rest = content.substring(cursor)
    if (rest.isNotBlank()) blocks += NoteBlock.Text(rest.trim())
    return blocks
}
