package com.xinjigalaxy.knownotes.ui.more

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinjigalaxy.knownotes.data.export.ExportFormat
import com.xinjigalaxy.knownotes.data.export.Exporter
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MoreViewModel(private val repo: NoteRepository) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    val stats: StateFlow<NoteRepository.Stats?> = refreshTick
        .flatMapLatest { flow { emit(repo.stats()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val deviceId: String = repo.deviceId
    val searchEngine: String = repo.searchEngineLabel
    val searchEngineIsFullText: Boolean = repo.searchEngineIsFullText
    val searchEngineProbeError: String? = repo.searchEngineProbeError
    val sqliteVersion: String = repo.sqliteVersion

    fun purgeDeleted(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val removed = repo.purgeDeleted()
            refreshTick.value += 1
            onDone(removed)
        }
    }
}

class ExportViewModel(
    private val repo: NoteRepository,
    private val exporter: Exporter,
) : ViewModel() {

    data class State(
        val format: ExportFormat = ExportFormat.JSON,
        val busy: Boolean = false,
        val message: String? = null,
        val stats: NoteRepository.Stats? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(stats = repo.stats()) } }
    }

    fun select(format: ExportFormat) = _state.update { it.copy(format = format, message = null) }

    fun suggestedName(): String = exporter.suggestedName(_state.value.format)

    fun export(target: Uri) {
        val format = _state.value.format
        val fileName = exporter.suggestedName(format)
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val result = exporter.export(format, target, fileName)
            _state.update { current ->
                current.copy(
                    busy = false,
                    message = result.fold(
                        onSuccess = { "已导出 ${it.fileName}（${formatBytes(it.bytes)}）" },
                        onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                    ),
                )
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "大小未知"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
