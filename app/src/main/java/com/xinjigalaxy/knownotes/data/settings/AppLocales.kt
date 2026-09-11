package com.xinjigalaxy.knownotes.data.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import java.util.Locale

/**
 * 应用语言的实际生效方式 —— 分两条路，因为 API 33 前后机制不一样：
 *
 * - **API 33+**：走系统 `LocaleManager`。它会持久化、和系统「设置 → 应用 → 语言」同步，
 *   并且改完**系统会自动重建 Activity**，不需要我们插手。清单里还声明了 `localeConfig`，
 *   所以系统那侧也能看到我们支持哪些语言。
 * - **API < 33**：没有 per-app language API，只能用「包一层 Configuration 的 Context」，
 *   在 Activity 的 `attachBaseContext` 里应用，改完由界面自己 `recreate()`。
 *
 * 两条路都只影响界面语言，不碰数据库里的任何内容。
 */
object AppLocales {

    /** per-app language API 从 Android 13（API 33）开始。 */
    const val PER_APP_LANGUAGE_API = Build.VERSION_CODES.TIRAMISU

    fun current(context: Context): AppLanguage = AppLanguage.fromTag(UiPrefs(context).appLanguage())

    /** API < 33 用：把语言包进 Context（跟随系统时原样返回）。 */
    fun wrap(base: Context, language: AppLanguage): Context {
        if (language == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * API 33+ 用：写进系统 LocaleManager（空列表 = 跟随系统）。
     * 低于 33 直接返回 false，表示「调用方需要自己 recreate()」。
     */
    fun apply(context: Context, language: AppLanguage): Boolean {
        if (Build.VERSION.SDK_INT < PER_APP_LANGUAGE_API) return false
        val manager = context.getSystemService(LocaleManager::class.java) ?: return false
        manager.applicationLocales =
            if (language == AppLanguage.SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(language.tag)
            }
        return true
    }
}
