package com.xinjigalaxy.knownotes.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染（需求文档 5.2：知识点常含代码片段，需要基础 Markdown）。
 *
 * 支持：`#` 标题（1~4 级）、**粗体**、*斜体*、~~删除线~~、`行内代码`、
 * 围栏代码块、`-`/`*`/`1.` 列表、`>` 引用、`---` 分隔线、[链接](url)（可点）。
 *
 * 有意不引第三方 Markdown 库：只读渲染自研两百行足够，
 * 少一个依赖 = 少一份包体、供应链风险与版本冲突。
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val colors = MarkdownColors(
        codeBackground = MaterialTheme.colorScheme.surfaceVariant,
        codeForeground = MaterialTheme.colorScheme.onSurfaceVariant,
        accent = MaterialTheme.colorScheme.primary,
        quote = MaterialTheme.colorScheme.tertiary,
    )
    val annotated = remember(text, colors) { renderMarkdown(text, colors) }
    Text(text = annotated, modifier = modifier, style = MaterialTheme.typography.bodyMedium)
}

private data class MarkdownColors(
    val codeBackground: Color,
    val codeForeground: Color,
    val accent: Color,
    val quote: Color,
)

private val INLINE = Regex(
    "(`[^`]+`)" +
        "|(\\*\\*[^*\\n]+\\*\\*)" +
        "|(__[^_\\n]+__)" +
        "|(~~[^~\\n]+~~)" +
        "|(\\[[^\\]\\n]+\\]\\([^)\\n]+\\))" +
        "|(\\*[^*\\n]+\\*)" +
        "|(_[^_\\n]+_)",
)

private val UNORDERED = Regex("^[-*+]\\s+")
private val ORDERED = Regex("^(\\d+)[.)]\\s+")

private fun renderMarkdown(source: String, colors: MarkdownColors): AnnotatedString =
    buildAnnotatedString {
        val lines = source.replace("\r\n", "\n").split("\n")
        val codeBuffer = StringBuilder()
        var inCode = false

        fun flushCode() {
            if (codeBuffer.isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = colors.codeBackground,
                        color = colors.codeForeground,
                    ),
                ) {
                    append(codeBuffer.toString().trimEnd('\n'))
                }
                codeBuffer.setLength(0)
            }
            inCode = false
        }

        lines.forEachIndexed { index, rawLine ->
            val trimmed = rawLine.trimStart()

            // 围栏代码块：整块等宽 + 底色，内部不再解析任何标记
            if (trimmed.startsWith("```")) {
                if (inCode) flushCode() else inCode = true
                return@forEachIndexed
            }
            if (inCode) {
                codeBuffer.append(rawLine).append('\n')
                return@forEachIndexed
            }

            if (index > 0) append('\n')

            when {
                trimmed.isEmpty() -> Unit

                trimmed.startsWith("#") -> appendHeading(trimmed, colors)

                trimmed.startsWith(">") ->
                    appendQuote(trimmed.removePrefix(">").trim(), colors)

                trimmed == "---" || trimmed == "***" || trimmed == "___" ->
                    withStyle(SpanStyle(color = colors.quote)) { append("─".repeat(24)) }

                UNORDERED.containsMatchIn(trimmed) -> {
                    val content = trimmed.replaceFirst(UNORDERED, "")
                    appendListItem("•", content, colors)
                }

                ORDERED.containsMatchIn(trimmed) -> {
                    val number = ORDERED.find(trimmed)?.groupValues?.get(1) ?: "1"
                    val content = trimmed.replaceFirst(ORDERED, "")
                    appendListItem("$number.", content, colors)
                }

                else -> appendInline(trimmed, colors)
            }
        }

        if (inCode) flushCode()
    }

private fun AnnotatedString.Builder.appendHeading(line: String, colors: MarkdownColors) {
    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
    val content = line.drop(level).trimStart()
    if (content.isEmpty()) {
        append("#".repeat(level))
        return
    }
    val size = when (level) {
        1 -> 1.5.em
        2 -> 1.3.em
        3 -> 1.15.em
        else -> 1.05.em
    }
    withStyle(SpanStyle(fontSize = size, fontWeight = FontWeight.Bold)) {
        appendInline(content, colors)
    }
}

private fun AnnotatedString.Builder.appendQuote(content: String, colors: MarkdownColors) {
    val style = SpanStyle(color = colors.quote, fontStyle = FontStyle.Italic)
    withStyle(style) {
        append("▏ ")
        appendInline(content, colors, style)
    }
}

private fun AnnotatedString.Builder.appendListItem(
    marker: String,
    content: String,
    colors: MarkdownColors,
) {
    // 负的 firstLine 让项目符号贴在左边，折行内容缩进对齐
    withStyle(ParagraphStyleIndent) {
        append(marker)
        append("  ")
        appendInline(content, colors)
    }
}

private val ParagraphStyleIndent
    get() = androidx.compose.ui.text.ParagraphStyle(
        textIndent = TextIndent(firstLine = (-18).sp, restLine = 18.sp),
    )

/** 行内标记：代码 → 粗体 → 删除线 → 链接 → 斜体（先长后短，避免误吞）。 */
private fun AnnotatedString.Builder.appendInline(
    line: String,
    colors: MarkdownColors,
    baseStyle: SpanStyle? = null,
) {
    fun emit(text: String) {
        if (text.isEmpty()) return
        if (baseStyle != null) withStyle(baseStyle) { append(text) } else append(text)
    }

    var cursor = 0
    INLINE.findAll(line).forEach { match ->
        if (match.range.first > cursor) emit(line.substring(cursor, match.range.first))

        val token = match.value
        when {
            token.startsWith("`") -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = colors.codeBackground,
                    color = colors.codeForeground,
                ),
            ) { append(token.trim('`')) }

            token.startsWith("**") || token.startsWith("__") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(token.substring(2, token.length - 2))
                }

            token.startsWith("~~") ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(token.substring(2, token.length - 2))
                }

            token.startsWith("[") -> {
                val close = token.indexOf(']')
                val label = token.substring(1, close)
                val url = token.substring(close + 2, token.length - 1)
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = colors.accent,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    ),
                ) { append(label) }
            }

            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(token.substring(1, token.length - 1))
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < line.length) emit(line.substring(cursor))
}
