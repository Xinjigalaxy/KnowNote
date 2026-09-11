package com.xinjigalaxy.knownotes.ui.manage

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.R
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

/** 「某个分组下的笔记」二级页面的数据。 */
class GroupNotesViewModel(
    private val repo: NoteRepository,
    private val prefs: UiPrefs,
) : ViewModel() {

    data class UiState(
        val title: String? = null,
        val notes: List<NoteWithTags> = emptyList(),
        val layout: NoteLayout = NoteLayout.LIST,
    )

    private val groupId = MutableStateFlow(0L)
    private val layout = MutableStateFlow(prefs.noteLayoutOrNull().toNoteLayout())

    val uiState: StateFlow<UiState> = combine(
        repo.observeGroups(),
        repo.observeNotes(),
        groupId,
        layout,
    ) { groups, notes, id, currentLayout ->
        UiState(
            title = groups.firstOrNull { it.id == id }?.name,
            notes = notes.filter { it.note.groupId == id },
            layout = currentLayout,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState())

    // 路由参数在组合后才可用，所以用 load 而不是构造参数
    fun load(id: Long) {
        if (groupId.value != id) groupId.value = id
    }

    fun toggleLayout() {
        layout.value = layout.value.next()
        prefs.saveNoteLayout(layout.value.name)
    }
}

@Composable
fun GroupNotesScreen(
    groupId: Long,
    onOpenNote: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    viewModel: GroupNotesViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    LaunchedEffect(groupId) { viewModel.load(groupId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NoteCollectionScreen(
        title = state.title ?: stringResource(R.string.groups),
        notes = state.notes,
        layout = state.layout,
        filterHint = stringResource(R.string.filter_within_this_group),
        onToggleLayout = viewModel::toggleLayout,
        onOpenNote = onOpenNote,
        onBack = onBack,
    )
}
