package com.xinjigalaxy.knownotes.ui.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinjigalaxy.knownotes.data.model.Group
import com.xinjigalaxy.knownotes.data.model.Tag
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NoteEditViewModel(
    private val repo: NoteRepository,
    private val prefs: UiPrefs,
) : ViewModel() {

    data class State(
        val noteId: Long? = null,
        val title: String = "",
        val content: String = "",
        val groupId: Long? = null,
        val tags: List<String> = emptyList(),
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
        val loaded: Boolean = false,
        val dirty: Boolean = false,
        val missing: Boolean = false,
        /** 预览模式：渲染 Markdown 而不是编辑原文（需求文档 5.2）。 */
        val preview: Boolean = false,
    ) {
        val isNew: Boolean get() = noteId == null
    }

    private val _state = MutableStateFlow(State(preview = prefs.preferMarkdownPreview))
    val state: StateFlow<State> = _state.asStateFlow()

    val allTags: StateFlow<List<Tag>> = repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val groups: StateFlow<List<Group>> = repo.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun load(noteId: Long?) {
        if (_state.value.loaded) return
        viewModelScope.launch {
            if (noteId == null || noteId == 0L) {
                _state.value = State(loaded = true, preview = prefs.preferMarkdownPreview)
                return@launch
            }
            val loaded = repo.note(noteId)
            _state.value = if (loaded == null) {
                State(loaded = true, missing = true)
            } else {
                State(
                    noteId = loaded.note.id,
                    title = loaded.note.title,
                    content = loaded.note.content,
                    groupId = loaded.note.groupId,
                    tags = loaded.tags.map { it.name },
                    createdAt = loaded.note.createdAt,
                    updatedAt = loaded.note.updatedAt,
                    loaded = true,
                    preview = prefs.preferMarkdownPreview,
                )
            }
        }
    }

    /** 编辑 / 预览切换，并记住偏好（下次打开同样的模式）。 */
    fun setPreview(value: Boolean) {
        _state.update { it.copy(preview = value) }
        prefs.preferMarkdownPreview = value
    }

    fun setTitle(value: String) = _state.update { it.copy(title = value, dirty = true) }

    fun setContent(value: String) = _state.update { it.copy(content = value, dirty = true) }

    fun setGroup(groupId: Long?) = _state.update { it.copy(groupId = groupId, dirty = true) }

    fun toggleTag(name: String) = _state.update { current ->
        val next = if (name in current.tags) current.tags - name else current.tags + name
        current.copy(tags = next, dirty = true)
    }

    fun addTag(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        _state.update { current -> current.copy(tags = (current.tags + clean).distinct(), dirty = true) }
    }

    fun removeTag(name: String) = _state.update { it.copy(tags = it.tags - name, dirty = true) }

    fun createGroup(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val id = repo.createGroup(clean)
            if (id > 0L) setGroup(id)
        }
    }

    /** 保存并回调结果 id；空白笔记（标题与正文都空）直接跳过。 */
    fun save(onSaved: (Long?) -> Unit) {
        viewModelScope.launch { onSaved(persist()) }
    }

    /** 返回时兜底保存，避免误退丢内容。 */
    fun saveOnExit(onFinished: () -> Unit) {
        viewModelScope.launch {
            persist()
            onFinished()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            _state.value.noteId?.let { repo.deleteNote(it) }
            onDeleted()
        }
    }

    private suspend fun persist(): Long? {
        val current = _state.value
        if (current.title.isBlank() && current.content.isBlank()) return current.noteId
        return runCatching {
            repo.saveNote(
                noteId = current.noteId,
                title = current.title,
                content = current.content,
                groupId = current.groupId,
                tagNames = current.tags,
            )
        }.onSuccess { newId ->
            _state.update {
                it.copy(
                    noteId = newId,
                    title = it.title.trim(),
                    updatedAt = System.currentTimeMillis(),
                    dirty = false,
                )
            }
        }.getOrNull()
    }
}
