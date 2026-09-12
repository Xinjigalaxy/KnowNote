package com.xinjigalaxy.knownotes.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
 * 围栏代码块、`-`/`*`/`1.` 列表、`>` 引用、`---` 分隔线、[链接](url)（可点），
 * 以及编辑器插入的自定义行内标记 `<color=red|yellow|green|cyan|blue|purple>…</color>` 与
 * `<size=0.85|1.25|1.5>…</size>`（字号是相对倍率，与阅读页的字号滑块叠加）。
 *
 * 有意不引第三方 Markdown 库：只读渲染自研两百行足够，
 * 少一个依赖 = 少一份包体、供应链风险与版本冲突。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    /** 字号倍率（1f = 正文默认）。标题用相对 em，所以会跟着一起缩放。 */
    fontScale: Float = 1f,
) {
    val base = MaterialTheme.typography.bodyMedium
    val scheme = MaterialTheme.colorScheme
    val colors = MarkdownColors(
        codeBackground = scheme.surfaceVariant,
        codeForeground = scheme.onSurfaceVariant,
        accent = scheme.primary,
        quote = scheme.tertiary,
        // 明暗看当前配色而不是系统设置：主题可以被用户强制成浅色 / 深色
        dark = scheme.surface.luminance() < 0.5f,
    )
    val annotated = remember(text, colors) { renderMarkdown(text, colors) }
    Text(
        text = annotated,
        modifier = modifier,
        style = base.copy(
            fontSize = base.fontSize * fontScale,
            lineHeight = base.lineHeight * fontScale,
        ),
    )
}

private data class MarkdownColors(
    val codeBackground: Color,
    val codeForeground: Color,
    val accent: Color,
    val quote: Color,
    /** 当前是深色主题 —— 行内颜色标记要据此在明暗两套色值里挑。 */
    val dark: Boolean,
)

/**
 * 行内标记可用颜色（红黄绿青蓝紫）。
 *
 * 每色两套取值：黄色在白底上根本读不清、深紫在黑底上也一样，
 * 所以按明暗分别给值，而不是一个色值打天下。
 */
enum class MarkupColor(val tag: String, private val light: Long, private val dark: Long) {
    RED("red", 0xFFC62828, 0xFFFF8A80),
    YELLOW("yellow", 0xFFB26A00, 0xFFFFE082),
    GREEN("green", 0xFF2E7D32, 0xFFA5D6A7),
    CYAN("cyan", 0xFF00838F, 0xFF80DEEA),
    BLUE("blue", 0xFF1565C0, 0xFF90CAF9),
    PURPLE("purple", 0xFF6A1B9A, 0xFFCE93D8),
    ;

    fun color(darkTheme: Boolean): Color = Color(if (darkTheme) dark else light)

    companion object {
        fun byTag(tag: String): MarkupColor? = entries.firstOrNull { it.tag == tag }
    }
}

/** 字号标记可用的相对倍率（相对当前阅读字号，所以能和阅读页的滑块叠加）。 */
enum class MarkupSize(val tag: String, val factor: Float) {
    SMALL("0.85", 0.85f),
    LARGE("1.25", 1.25f),
    XLARGE("1.5", 1.5f),
    ;

    companion object {
        fun byTag(tag: String): MarkupSize? = entries.firstOrNull { it.tag == tag }
    }
}

/** 行内颜色 / 字号标记的正则片段（内容允许再嵌其它行内标记，靠递归解析）。 */
private const val COLOR_TAGS = "(<color=(?:red|yellow|green|cyan|blue|purple)>[^\\n]*?</color>)"
private const val SIZE_TAGS = "(<size=(?:0\\.85|1\\.25|1\\.5)>[^\\n]*?</size>)"

private val INLINE = Regex(
    COLOR_TAGS +
        "|" + SIZE_TAGS +
        "|(`[^`]+`)" +
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

/**
 * 单元测试入口：用固定配色跑同一套渲染逻辑。
 *
 * 标记解析（尤其 `<color>` / `<size>` 的嵌套、以及遇到不认识的标记要原样降级）
 * 最容易出错，而它本质是纯字符串处理，不该只能靠肉眼看界面来验证。
 */
internal fun renderMarkdownForTest(source: String, dark: Boolean = false): AnnotatedString =
    renderMarkdown(
        source,
        MarkdownColors(
            codeBackground = Color(0xFFE8E8E8),
            codeForeground = Color(0xFF202020),
            accent = Color(0xFF006A6A),
            quote = Color(0xFF6F7979),
            dark = dark,
        ),
    )

/** 把可空的基础样式收敛成可合并的样式。 */
private fun SpanStyle?.orEmpty(): SpanStyle = this ?: SpanStyle()

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
            // <color=xxx>…</color>：内容递归解析，所以里面还能写 **粗体**、`代码`
            token.startsWith("<color=") -> {
                val tag = token.substringAfter('=').substringBefore('>')
                val inner = token.substringAfter('>').removeSuffix("</color>")
                val color = MarkupColor.byTag(tag)?.color(colors.dark)
                if (color == null) {
                    emit(token)
                } else {
                    appendInline(inner, colors, baseStyle.orEmpty().merge(SpanStyle(color = color)))
                }
            }

            // <size=1.25>…</size>：em 是相对量，所以能和阅读页的字号滑块叠加
            token.startsWith("<size=") -> {
                val tag = token.substringAfter('=').substringBefore('>')
                val inner = token.substringAfter('>').removeSuffix("</size>")
                val factor = MarkupSize.byTag(tag)?.factor
                if (factor == null) {
                    emit(token)
                } else {
                    appendInline(inner, colors, baseStyle.orEmpty().merge(SpanStyle(fontSize = factor.em)))
                }
            }

            token.startsWith("`") -> withStyle(
                baseStyle.orEmpty().merge(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = colors.codeBackground,
                        color = colors.codeForeground,
                    ),
                ),
            ) { append(token.trim('`')) }

            token.startsWith("**") || token.startsWith("__") -> {
                val style = baseStyle.orEmpty().merge(SpanStyle(fontWeight = FontWeight.Bold))
                appendInline(token.substring(2, token.length - 2), colors, style)
            }

            token.startsWith("~~") -> {
                val style = baseStyle.orEmpty().merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendInline(token.substring(2, token.length - 2), colors, style)
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

            else -> {
                val style = baseStyle.orEmpty().merge(SpanStyle(fontStyle = FontStyle.Italic))
                appendInline(token.substring(1, token.length - 1), colors, style)
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < line.length) emit(line.substring(cursor))
}
