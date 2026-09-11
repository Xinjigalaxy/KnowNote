package com.xinjigalaxy.knownotes.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 回收站：软删除笔记的恢复 / 彻底删除 / 清空。 */
class TrashViewModel(private val repo: NoteRepository) : ViewModel() {

    val notes: StateFlow<List<NoteWithTags>> = repo.observeDeleted()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun restore(id: Long) {
        viewModelScope.launch { repo.restoreNote(id) }
    }

    fun purge(id: Long) {
        viewModelScope.launch { repo.purgeNote(id) }
    }

    fun purgeAll(onDone: (Int) -> Unit) {
        viewModelScope.launch { onDone(repo.purgeDeleted()) }
    }
}
