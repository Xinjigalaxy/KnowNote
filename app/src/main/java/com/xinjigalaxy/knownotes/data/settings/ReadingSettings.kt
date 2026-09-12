package com.xinjigalaxy.knownotes.data.settings

import androidx.annotation.StringRes
import com.xinjigalaxy.knownotes.R

/**
 * 阅读相关的显示设置（v1.6.0）：字号缩放 + 文字颜色。
 *
 * 两个刻意的设计：
 * - 字号用**比例**而不是绝对 sp：整棵 Typography 按比例缩放，标题/正文的相对层级不会乱。
 * - 文字颜色存的是**选项**、不存 ARGB：同一个"暖褐"在浅色下必须是深褐、深色下必须是浅褐，
 *   否则暗色主题里选它就直接看不见。具体色值按当前明暗主题解析（见 colorFor）。
 */
enum class FontScale(val factor: Float) {
    SMALL(0.88f),
    NORMAL(1.0f),
    LARGE(1.15f),
    XLARGE(1.30f),
    HUGE(1.45f),
    ;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            SMALL -> R.string.font_scale_small
            NORMAL -> R.string.font_scale_normal
            LARGE -> R.string.font_scale_large
            XLARGE -> R.string.font_scale_xlarge
            HUGE -> R.string.font_scale_huge
        }

    companion object {
        fun fromName(name: String?): FontScale =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

enum class TextColorOption(val light: Long?, val dark: Long?) {
    /** 跟随主题（默认）：不覆盖，用 M3 的 onSurface 系。 */
    THEME(null, null),
    INK(0xFF37474F, 0xFFDDE5E6),
    BLACK(0xFF000000, 0xFFFFFFFF),
    SEPIA(0xFF5B4636, 0xFFE8D6C3),
    FOREST(0xFF1F3D2B, 0xFFBEE3CB),
    NAVY(0xFF1B2A4A, 0xFFC6D7F5),
    WINE(0xFF4A1F28, 0xFFF3C8D1),
    ;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            THEME -> R.string.text_color_theme
            INK -> R.string.text_color_ink
            BLACK -> R.string.text_color_black
            SEPIA -> R.string.text_color_sepia
            FOREST -> R.string.text_color_forest
            NAVY -> R.string.text_color_navy
            WINE -> R.string.text_color_wine
        }

    /** 该选项在当前明暗下的 ARGB；THEME 返回 null 表示"不覆盖"。 */
    fun colorFor(darkTheme: Boolean): Long? = if (darkTheme) this.dark else light

    companion object {
        fun fromName(name: String?): TextColorOption =
            entries.firstOrNull { it.name == name } ?: THEME
    }
}

/**
 * 阅读页的展示方式（v1.7.0）：渲染 Markdown，还是原样看文本。
 *
 * 两者都有人需要 —— 渲染适合读，原文适合改（尤其带 `<color>` / `<size>` 这类行内标记时，
 * 只有原文模式才能看出标记到底长什么样）。
 */
enum class ReadMode(val prefName: String) {
    MD("md"),
    TEXT("text"),
    ;

    companion object {
        fun fromName(name: String?): ReadMode = entries.firstOrNull { it.prefName == name } ?: MD
    }
}

/** 阅读页字号倍率的可调范围（滑块用）。 */
const val READ_FONT_MIN = 0.8f
const val READ_FONT_MAX = 1.8f
const val READ_FONT_DEFAULT = 1.0f
