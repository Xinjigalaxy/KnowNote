package com.xinjigalaxy.knownotes.data.markup

/**
 * 行内标记（编辑器工具栏写进正文的自定义语法）的「词法」部分。
 *
 * 放在 data 层而不是渲染器旁边，是因为两边都要用：
 * - 渲染器（ui）按它把标记渲染成颜色 / 字号；
 * - 列表摘要与全文索引要**去掉**标记 —— 标记是写给渲染器看的，不是给人读的，
 *   也不该让 `color` / `size` 这种标签名污染全文检索。
 *
 * 词表只认渲染器支持的那几档（六色 + 三档字号）：手打的 `<color=pink>` 渲染器会原样显示，
 * 这里也就不该把它抹掉 —— 两处行为保持一致，用户看到什么就是什么。
 */
object InlineMarkup {

    private const val COLORS = "red|yellow|green|cyan|blue|purple"
    private const val SIZES = "0\\.85|1\\.25|1\\.5"

    /**
     * 成对标记：整体换成里面的内容。
     *
     * 两处细节都是必要的：
     * - 内容用 `[^<]*?`（不含尖括号）而不是 `.*?` —— 否则惰性匹配会**跨标签配对**：
     *   `<color=blue><size=1.5>x</size></color>` 会被切成 `x` 加一个落单的 `</color>`；
     * - 收尾用反向引用 `</\1>`，保证 color 只跟 color 配对（写错成 `</size>` 的不吃）。
     */
    private val PAIR = Regex(
        "<(color|size)=(?:$COLORS|$SIZES)>([^<]*?)</\\1>",
    )

    /**
     * 剩下的「标签形状」的东西一律清掉：只删了一半的、渲染器不认的（手打 `<color=pink>`）都算。
     *
     * 这里与渲染器**故意不一致**：渲染器对不认识的标记会原样显示（不猜颜色、也不吃掉文字），
     * 而摘要的职责是「能读」，不是忠实呈现语法。正文本身一个字符都没动，想看原样切「文本模式」即可。
     */
    private val STRAY = Regex("</?(?:color|size)(?:=[^<>]*)?>")

    /**
     * 去掉自定义标记、只留可读文字（**索引**用这一档）。
     *
     * 先整体换掉成对标记，再清掉落单的 —— 顺序不能反：先清落单会把 `<color=red>` 提前吃掉，
     * 留下一堆没有开头的 `</color>`。
     */
    fun plain(text: String): String {
        if (!text.contains('<')) return text
        return STRAY.replace(PAIR.replace(text) { it.groupValues[2] }, "")
    }

    // 标准 Markdown 的行内记号：摘要里也去掉，只留文字
    private val BOLD_STAR = Regex("\\*\\*([^*\\n]+)\\*\\*")
    private val BOLD_UNDER = Regex("(?<![\\w])__([^_\\n]+?)__(?![\\w])")
    private val STRIKE = Regex("~~([^~\\n]+)~~")
    private val CODE = Regex("`([^`\\n]+)`")

    /** 斜体：两侧不能贴字母数字，所以 `2*3*4` 这种算式不会被误吃。 */
    private val EMPH_STAR = Regex("(?<![\\w*])\\*([^\\s*][^*\\n]*?)(?<![\\s*])\\*(?![\\w*])")

    /** 下划线斜体必须卡词边界，否则 `my_file_name` 会被误伤成 `myfilename`。 */
    private val EMPH_UNDER = Regex("(?<![\\w_])_([^\\s_][^_\\n]*?)(?<![\\s_])_(?![\\w_])")

    /**
     * 摘要用：自定义标记 + 标准 Markdown 行内记号全部去掉，只留可读文字。
     *
     * 列表卡片是"扫一眼"的地方，`**标记**`、`` `代码` `` 这些记号在正文里是有意义的，
     * 堆在摘要里只会把真正的内容挤掉。正文本身一个字符都不动 ——
     * 想看原样，阅读页切「文本模式」即可。
     */
    fun summary(text: String): String {
        if (text.isEmpty()) return text
        var out = plain(text)
        out = BOLD_STAR.replace(out) { it.groupValues[1] }
        out = BOLD_UNDER.replace(out) { it.groupValues[1] }
        out = STRIKE.replace(out) { it.groupValues[1] }
        out = CODE.replace(out) { it.groupValues[1] }
        out = EMPH_STAR.replace(out) { it.groupValues[1] }
        out = EMPH_UNDER.replace(out) { it.groupValues[1] }
        return out
    }
}
