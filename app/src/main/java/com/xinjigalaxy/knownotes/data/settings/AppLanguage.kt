package com.xinjigalaxy.knownotes.data.settings

import androidx.annotation.StringRes
import com.xinjigalaxy.knownotes.R

/**
 * 应用内语言（v1.5.0）。
 *
 * 两个刻意的选择：
 * - 「跟随系统」用空 tag 表示，交给系统自己决定；
 * - 语言名（简体中文 / 繁體中文 / English / 日本語）四个语言包里都写**本语言的名字**，并且
 *   标了 `translatable="false"` —— 用户看不懂当前界面语言时，得能认出自己想选哪个。
 *   Android 官方的语言选择器就是这么做的。
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ZH_CN("zh-CN"),
    ZH_TW("zh-TW"),
    EN("en"),
    JA("ja"),
    ;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            SYSTEM -> R.string.lang_system
            ZH_CN -> R.string.lang_zh_cn
            ZH_TW -> R.string.lang_zh_tw
            EN -> R.string.lang_en
            JA -> R.string.lang_ja
        }

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: SYSTEM
    }
}
