package com.xinjigalaxy.knownotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinjigalaxy.knownotes.R
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import com.xinjigalaxy.knownotes.data.settings.AppLanguage
import com.xinjigalaxy.knownotes.data.settings.AppSettings
import com.xinjigalaxy.knownotes.data.settings.ThemeMode
import com.xinjigalaxy.knownotes.ui.components.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页（外观 + 回收站清理）。
 *
 * @param scheduleCleanup 开关定时清理时回调，由容器接上 WorkManager —— 这样 ViewModel 里
 *        不用拿着 Context，测试也只需要传一个 lambda。
 */
class SettingsViewModel(
    private val repo: NoteRepository,
    private val settings: AppSettings,
    private val prefs: UiPrefs,
    private val scheduleCleanup: (Boolean) -> Unit,
    private val applyLocale: (AppLanguage) -> Unit,
) : ViewModel() {

    data class UiState(
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val dynamicColor: Boolean = false,
        val autoPurgeTrash: Boolean = false,
        val retentionDays: Int = AppSettings.DEFAULT_RETENTION_DAYS,
        val retentionChoices: List<Int> = AppSettings.RETENTION_CHOICES,
        val trashCount: Int = 0,
        val lastPurgeAt: Long = 0L,
        val lastPurgeCount: Int = 0,
        val language: AppLanguage = AppLanguage.SYSTEM,
        val busy: Boolean = false,
        val message: UiMessage? = null,
    )

    private data class Local(
        val lastPurgeAt: Long = 0L,
        val lastPurgeCount: Int = 0,
        val busy: Boolean = false,
        val message: UiMessage? = null,
    )

    private val local = MutableStateFlow(
        Local(
            lastPurgeAt = prefs.lastTrashPurgeAt(),
            lastPurgeCount = prefs.lastTrashPurgeCount(),
        )
    )

    val uiState: StateFlow<UiState> = combine(
        settings.state,
        repo.observeDeletedCount(),
        local,
    ) { app, trashCount, l ->
        UiState(
            themeMode = app.themeMode,
            dynamicColor = app.dynamicColor,
            autoPurgeTrash = app.autoPurgeTrash,
            retentionDays = app.trashRetentionDays,
            trashCount = trashCount,
            lastPurgeAt = l.lastPurgeAt,
            lastPurgeCount = l.lastPurgeCount,
            language = app.language,
            busy = l.busy,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState())

    /**
     * 切语言：写偏好 + 通知系统（33+ 由 LocaleManager 重建 Activity，
     * 低版本由界面自己 recreate()，见 SettingsScreen）。
     */
    fun setLanguage(language: AppLanguage) {
        settings.setLanguage(language)
        applyLocale(language)
    }

    fun setThemeMode(mode: ThemeMode) {
        settings.setThemeMode(mode)
        local.update { it.copy(message = null) }
    }

    fun setDynamicColor(enabled: Boolean) = settings.setDynamicColor(enabled)

    fun setAutoPurgeTrash(enabled: Boolean) {
        settings.setAutoPurgeTrash(enabled)
        // 开关与周期任务必须一起动：只改设置不排任务 = 用户以为清了其实没清
        scheduleCleanup(enabled)
        local.update {
            it.copy(
                message = if (enabled) {
                    UiMessage(R.string.on_the_system_cleans_the_trash_once_a_day)
                } else {
                    UiMessage(R.string.scheduled_cleanup_off_trashed_notes_are_kept_ind)
                },
            )
        }
    }

    fun setRetentionDays(days: Int) = settings.setTrashRetentionDays(days)

    /** 立即按保留天数清一次，不用等定时任务。 */
    fun purgeNow() {
        val days = settings.state.value.trashRetentionDays
        local.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val removed = runCatching { repo.purgeTrashOlderThan(days) }.getOrElse { -1 }
            if (removed >= 0) {
                prefs.saveLastTrashPurge(System.currentTimeMillis(), removed)
            }
            local.update {
                it.copy(
                    busy = false,
                    lastPurgeAt = if (removed >= 0) System.currentTimeMillis() else it.lastPurgeAt,
                    lastPurgeCount = if (removed >= 0) removed else it.lastPurgeCount,
                    message = if (removed < 0) {
                        UiMessage(R.string.cleanup_failed_try_again)
                    } else if (removed == 0) {
                        UiMessage(R.string.no_notes_older_than_days_days_to_clean_up, listOf(days))
                    } else {
                        UiMessage(
                            R.string.permanently_deleted_removed_notes_older_than_day,
                            listOf(removed, days),
                        )
                    },
                )
            }
        }
    }

    fun consumeMessage() = local.update { it.copy(message = null) }
}
