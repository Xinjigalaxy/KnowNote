package com.xinjigalaxy.knownotes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import com.xinjigalaxy.knownotes.data.settings.FontScale
import com.xinjigalaxy.knownotes.data.settings.TextColorOption

/**
 * 主题种子色是初音绿 #39C5BB，按 Material 3 的色彩角色手工铺开。
 * 默认不吃 Android 12+ 的动态取色，保住品牌色；需要跟随壁纸时把 dynamicColor 打开即可。
 */
private val MikuTealSeed = Color(0xFF39C5BB)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A6A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F0),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E7),
    onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF4B607C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF041C35),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FBFA),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFF7FBFA),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE5E4),
    onSurfaceVariant = Color(0xFF3F4948),
    outline = Color(0xFF6F7979),
    outlineVariant = Color(0xFFBEC9C8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CDADB),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F50),
    onPrimaryContainer = Color(0xFF6FF7F6),
    secondary = Color(0xFFB0CCCB),
    onSecondary = Color(0xFF1B3534),
    secondaryContainer = Color(0xFF324B4B),
    onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFB3C8E8),
    onTertiary = Color(0xFF1C314B),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD3E4FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C1C),
    onBackground = Color(0xFFE0E3E2),
    surface = Color(0xFF191C1C),
    onSurface = Color(0xFFE0E3E2),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C8),
    outline = Color(0xFF899393),
    outlineVariant = Color(0xFF3F4948),
)

@Composable
fun KnowNoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    fontScale: FontScale = FontScale.NORMAL,
    textColor: TextColorOption = TextColorOption.THEME,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }
    val typography = remember(fontScale) { Typography().scaledBy(fontScale.factor) }
    val tint = textColor.colorFor(darkTheme)?.let { Color(it) }
    // 文字颜色的介入点是**主题色角色**，不是 LocalContentColor ——
    // 页面里的 Text 有 60 多处显式写着 onSurface / onSurfaceVariant / tertiary，
    // 显式颜色会盖掉 LocalContentColor，只在主题上改角色才真正生效。
    // primary（高亮、标签）与 outline（时间戳、次要提示）刻意不动：点缀保持灰阶与强调色。
    val scheme = if (tint == null) {
        colorScheme
    } else {
        colorScheme.copy(
            onSurface = tint,
            onBackground = tint,
            onSurfaceVariant = tint,
            tertiary = tint,
        )
    }

    MaterialTheme(colorScheme = scheme, typography = typography) {
        // LocalContentColor / LocalTextStyle 作为兜底：没写 style 或 color 的 Text
        // （比如各页面的说明文字）也要跟着字号与文字颜色走。
        CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.bodyLarge,
            LocalContentColor provides (tint ?: LocalContentColor.current),
        ) {
            content()
        }
    }
}

/**
 * 整棵 Typography 按比例缩放。
 *
 * 只动 fontSize / lineHeight：字重、字距、层级关系全部保持不变，
 * 所以"标题比正文大"这种相对关系不会因为调字号而乱掉。
 */
private fun Typography.scaledBy(factor: Float): Typography {
    if (factor == 1f) return this
    fun TextStyle.scaled(): TextStyle =
        copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)

    return copy(
        displayLarge = displayLarge.scaled(),
        displayMedium = displayMedium.scaled(),
        displaySmall = displaySmall.scaled(),
        headlineLarge = headlineLarge.scaled(),
        headlineMedium = headlineMedium.scaled(),
        headlineSmall = headlineSmall.scaled(),
        titleLarge = titleLarge.scaled(),
        titleMedium = titleMedium.scaled(),
        titleSmall = titleSmall.scaled(),
        bodyLarge = bodyLarge.scaled(),
        bodyMedium = bodyMedium.scaled(),
        bodySmall = bodySmall.scaled(),
        labelLarge = labelLarge.scaled(),
        labelMedium = labelMedium.scaled(),
        labelSmall = labelSmall.scaled(),
    )
}

/** 供界面里做点缀用的品牌色。 */
val BrandMikuTeal = MikuTealSeed
