package com.xinjigalaxy.knownotes.ui.markdown

/** 施加标记后的结果：新文本 + 新选区（用 Int 表达，便于不依赖 Compose 单测）。 */
data class MarkupResult(val text: String, val start: Int, val end: Int)

/**
 * 编辑器选区格式化（v1.7.0）。
 *
 * 全是纯字符串操作，刻意不碰 Compose 的 TextRange —— 这样"包标记 / 摘标记 / 换颜色"
 * 这些最容易出错的边界（选区在文首、空选区、已经包过一层）可以全部单测覆盖。
 */
object MarkupEdit {

    private val COLOR_ANY = Regex("<color=[a-z]+>(.*)</color>", RegexOption.DOT_MATCHES_ALL)
    private val SIZE_ANY = Regex("<size=[0-9.]+>(.*)</size>", RegexOption.DOT_MATCHES_ALL)
    private val COLOR_OPEN_ANY = Regex("<color=[a-z]+>")
    private val COLOR_CLOSE_ANY = Regex("</color>")
    private val SIZE_OPEN_ANY = Regex("<size=[0-9.]+>")
    private val SIZE_CLOSE_ANY = Regex("</size>")

    /** 判断标记是否紧贴选区时，只看选区两侧这么长的窗口（最长的标记也就十几个字符）。 */
    private const val TAG_WINDOW = 32

    /**
     * 包一层标记，返回值给界面用来恢复选区。
     *
     * 三种情况：
     * - 选中了文字 → 包住它，新选区仍是**那段文字**（方便接着再加别的样式）
     * - 没选中（光标）→ 插入一对空标记，光标停在中间，接着打字就是想要的样式
     * - 选区已经带着同样的标记 → 再点一次**摘掉**（开关式，这是粗体/斜体该有的手感）
     */
    fun wrap(text: String, start: Int, end: Int, open: String, close: String): MarkupResult {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val selected = text.substring(s, e)

        // 选中的文本自己就带着同样的标记（用户把 **文字** 连星号一起选中了）→ 摘掉
        if (selected.length >= open.length + close.length &&
            selected.startsWith(open) && selected.endsWith(close)
        ) {
            val inner = selected.substring(open.length, selected.length - close.length)
            return MarkupResult(text.replaceRange(s, e, inner), s, s + inner.length)
        }

        // 标记紧贴选区外侧（连着标记一起选了两端）→ 摘掉
        val outerStart = s - open.length
        val outerEnd = e + close.length
        if (outerStart >= 0 && outerEnd <= text.length &&
            text.regionMatches(outerStart, open, 0, open.length) &&
            text.regionMatches(e, close, 0, close.length)
        ) {
            val stripped = text.removeRange(e, outerEnd).removeRange(outerStart, s)
            return MarkupResult(stripped, outerStart, outerStart + selected.length)
        }

        val newText = text.replaceRange(s, e, open + selected + close)
        val innerStart = s + open.length
        return MarkupResult(newText, innerStart, innerStart + selected.length)
    }

    fun bold(text: String, start: Int, end: Int) = wrap(text, start, end, "**", "**")

    fun italic(text: String, start: Int, end: Int) = wrap(text, start, end, "*", "*")

    /**
     * 上色 / 改字号：**先认出现有的同类标记并替换掉**，再包新的。
     *
     * 不做这一步，红改蓝会叠成 `<color=blue><color=red>x</color></color>` —— 渲染上没错，
     * 但原文越点越乱，用户想手改都难以下手。
     *
     * 两种"已有标记"的形态都要认：
     * ① 用户把标记一起选中了（`<color=red>x</color>` 整段）
     * ② 只选中了里面的文字（标记紧贴选区外侧）—— 这是绝大多数情况，
     *    因为正常操作是拖过文字，不会精确到把尖括号也框进来
     */
    private fun retag(
        text: String,
        start: Int,
        end: Int,
        open: String,
        close: String,
        pattern: Regex,
        openAny: Regex,
        closeAny: Regex,
    ): MarkupResult {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val selected = text.substring(s, e)

        // ① 整个选区就是一层同类标记 → 换值，内部原样保留
        val whole = pattern.matchEntire(selected)
        if (whole != null) {
            val inner = whole.groupValues[1]
            val newText = text.replaceRange(s, e, open + inner + close)
            val innerStart = s + open.length
            return MarkupResult(newText, innerStart, innerStart + inner.length)
        }

        // ② 标记紧贴选区外侧 → 把外侧那一对换成新的
        val beforeWindow = text.substring(maxOf(0, s - TAG_WINDOW), s)
        val openMatch = openAny.findAll(beforeWindow).lastOrNull()
            ?.takeIf { it.range.last == beforeWindow.length - 1 }
        val afterWindow = text.substring(e, minOf(text.length, e + TAG_WINDOW))
        val closeMatch = closeAny.find(afterWindow)?.takeIf { it.range.first == 0 }
        if (openMatch != null && closeMatch != null) {
            val prefix = text.substring(0, s - openMatch.value.length)
            val suffix = text.substring(e + closeMatch.value.length)
            val newText = prefix + open + selected + close + suffix
            val innerStart = prefix.length + open.length
            return MarkupResult(newText, innerStart, innerStart + selected.length)
        }

        // ③ 没有已有标记 → 直接包一层
        val newText = text.replaceRange(s, e, open + selected + close)
        val innerStart = s + open.length
        return MarkupResult(newText, innerStart, innerStart + selected.length)
    }

    fun recolor(text: String, start: Int, end: Int, color: MarkupColor): MarkupResult =
        retag(text, start, end, "<color=${color.tag}>", "</color>", COLOR_ANY, COLOR_OPEN_ANY, COLOR_CLOSE_ANY)

    fun resize(text: String, start: Int, end: Int, size: MarkupSize): MarkupResult =
        retag(text, start, end, "<size=${size.tag}>", "</size>", SIZE_ANY, SIZE_OPEN_ANY, SIZE_CLOSE_ANY)

    /**
     * 工具栏按钮的"是否已生效"判定：选区所在的这一段是否已经带该标记。
     * 只用于按钮的选中态高亮，判断保守一点没关系（宁可不高亮，也不要高亮错）。
     */
    fun hasMarkup(text: String, start: Int, end: Int, open: String, close: String): Boolean {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val selected = text.substring(s, e)
        if (selected.length >= open.length + close.length &&
            selected.startsWith(open) && selected.endsWith(close)
        ) {
            return true
        }
        val outerStart = s - open.length
        val outerEnd = e + close.length
        return outerStart >= 0 && outerEnd <= text.length &&
            text.regionMatches(outerStart, open, 0, open.length) &&
            text.regionMatches(e, close, 0, close.length)
    }
}
