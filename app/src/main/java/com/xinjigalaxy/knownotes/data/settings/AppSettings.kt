package com.xinjigalaxy.knownotes.data.settings

import androidx.annotation.StringRes
import com.xinjigalaxy.knownotes.R
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 主题模式：跟随系统 / 强制浅色 / 强制深色。 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            SYSTEM -> R.string.system_default
            LIGHT -> R.string.light
            DARK -> R.string.dark
        }

    companion object {
        fun fromName(value: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * 应用级设置（外观 + 回收站清理）。
 *
 * 为什么要有这个类：**SharedPreferences 本身不是响应式的**。主题改完只写偏好，
 * 界面不会重组；这里持一份 StateFlow，读写都经过它，Compose 直接 collect 就能立刻换肤。
 */
class AppSettings(private val prefs: UiPrefs) {

    data class State(
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val dynamicColor: Boolean = false,
        val autoPurgeTrash: Boolean = false,
        val trashRetentionDays: Int = DEFAULT_RETENTION_DAYS,
        val language: AppLanguage = AppLanguage.SYSTEM,
        val fontScale: FontScale = FontScale.NORMAL,
        val textColor: TextColorOption = TextColorOption.THEME,
    )

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun read() = State(
        themeMode = ThemeMode.fromName(prefs.themeMode()),
        dynamicColor = prefs.dynamicColor(),
        autoPurgeTrash = prefs.autoPurgeTrash(),
        trashRetentionDays = prefs.trashRetentionDays(),
        language = AppLanguage.fromTag(prefs.appLanguage()),
        fontScale = FontScale.fromName(prefs.fontScale()),
        textColor = TextColorOption.fromName(prefs.textColor()),
    )

    fun setLanguage(language: AppLanguage) {
        prefs.saveAppLanguage(language.tag)
        _state.update { it.copy(language = language) }
    }

    fun setFontScale(scale: FontScale) {
        prefs.saveFontScale(scale.name)
        _state.update { it.copy(fontScale = scale) }
    }

    fun setTextColor(option: TextColorOption) {
        prefs.saveTextColor(option.name)
        _state.update { it.copy(textColor = option) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.saveThemeMode(mode.name)
        _state.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.saveDynamicColor(enabled)
        _state.update { it.copy(dynamicColor = enabled) }
    }

    fun setAutoPurgeTrash(enabled: Boolean) {
        prefs.saveAutoPurgeTrash(enabled)
        _state.update { it.copy(autoPurgeTrash = enabled) }
    }

    fun setTrashRetentionDays(days: Int) {
        prefs.saveTrashRetentionDays(days)
        _state.update { it.copy(trashRetentionDays = days) }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30

        /** 保留天数的可选项。 */
        val RETENTION_CHOICES = listOf(7, 30, 90)
    }
}
