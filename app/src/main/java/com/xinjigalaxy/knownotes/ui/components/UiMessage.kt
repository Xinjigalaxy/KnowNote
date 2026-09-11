package com.xinjigalaxy.knownotes.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 非可组合层（ViewModel / 数据层）携带的待本地化文案：资源 id + 参数，正文一律留在资源里。
 *
 * 一条提示可能由多段资源拼成（例：「同步完成：拉到 …」+「推过去 …」+「时间戳打平 …」），
 * 所以内部按段存；参数也可以再嵌一个 [UiMessage]（例：没有局域网地址时把「本机 IP」塞进占位符）。
 */
data class UiMessage(val parts: List<Part>) {

    data class Part(@StringRes val resId: Int, val args: List<Any> = emptyList())

    constructor(@StringRes resId: Int, args: List<Any> = emptyList()) : this(listOf(Part(resId, args)))

    companion object {
        /** 顺序拼接若干段，null 表示该段不出现（用于「主句 + 可选从句」）。 */
        fun concat(vararg messages: UiMessage?): UiMessage =
            UiMessage(messages.filterNotNull().flatMap { it.parts })
    }
}

/** 渲染成当前 Locale 的完整文案；界面侧直接 `Text(msg.rendered())` 即可。 */
@Composable
fun UiMessage.rendered(): String = parts.map { part ->
    stringResource(part.resId, *part.args.map { arg -> argText(arg) }.toTypedArray())
}.joinToString("")

@Composable
private fun argText(arg: Any): String = if (arg is UiMessage) arg.rendered() else arg.toString()
