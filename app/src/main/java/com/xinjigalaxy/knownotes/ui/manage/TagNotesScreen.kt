package com.xinjigalaxy.knownotes.ui.manage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.NoteLayout
import com.xinjigalaxy.knownotes.ui.components.NoteCollectionScreen
import com.xinjigalaxy.knownotes.ui.toNoteLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 「某个标签下的笔记」二级页面的数据。 */
class TagNotesViewModel(
    private val repo: NoteRepository,
    private val prefs: UiPrefs,
) : ViewModel() {

    data class UiState(
        val title: String = "标签",
        val notes: List<NoteWithTags> = emptyList(),
        val groupNames: Map<Long, String> = emptyMap(),
        val layout: NoteLayout = NoteLayout.LIST,
    )

    private val tagId = MutableStateFlow(0L)
    private val layout = MutableStateFlow(prefs.noteLayoutOrNull().toNoteLayout())

    val uiState: StateFlow<UiState> = combine(
        repo.observeTags(),
        repo.observeGroups(),
        repo.observeNotes(),
        tagId,
        layout,
    ) { tags, groups, notes, id, currentLayout ->
        val tag = tags.firstOrNull { it.id == id }
        UiState(
            title = tag?.let { "#${it.name}" } ?: "标签",
            notes = notes.filter { item -> item.tags.any { it.id == id } },
            groupNames = groups.associate { it.id to it.name },
            layout = currentLayout,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState())

    fun load(id: Long) {
        if (tagId.value != id) tagId.value = id
    }

    fun toggleLayout() {
        layout.value = layout.value.next()
        prefs.saveNoteLayout(layout.value.name)
    }
}

@Composable
fun TagNotesScreen(
    tagId: Long,
    onOpenNote: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    viewModel: TagNotesViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    LaunchedEffect(tagId) { viewModel.load(tagId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NoteCollectionScreen(
        title = state.title,
        notes = state.notes,
        groupNames = state.groupNames,
        layout = state.layout,
        filterHint = "在本标签内筛选",
        onToggleLayout = viewModel::toggleLayout,
        onOpenNote = onOpenNote,
        onBack = onBack,
    )
}
