package com.xinjigalaxy.knownotes.ui.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinjigalaxy.knownotes.data.fts.FtsText
import com.xinjigalaxy.knownotes.data.model.Group
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.data.model.Tag
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 分组筛选哨兵：真实 group_id 从 1 开始自增。 */
const val GROUP_ALL = -1L
const val GROUP_NONE = -2L

data class NoteListUiState(
    val query: String = "",
    val highlightTerms: List<String> = emptyList(),
    val notes: List<NoteWithTags> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val groups: List<Group> = emptyList(),
    val selectedTagIds: Set<Long> = emptySet(),
    val groupFilter: Long = GROUP_ALL,
    val totalCount: Int = 0,
    val filtered: Boolean = false,
    val loading: Boolean = true,
    val searchEngine: String = "",
    val searchEngineIsFullText: Boolean = true,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NoteListViewModel(private val repo: NoteRepository) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedTagIds = MutableStateFlow<Set<Long>>(emptySet())
    private val groupFilter = MutableStateFlow(GROUP_ALL)

    /** 输入即搜，防抖 300ms（需求文档 3.4）。 */
    private val debouncedQuery = query
        .debounce(300L)
        .map { it.trim() }
        .distinctUntilChanged()

    /** FTS5 命中的 id（按 bm25 相关度排序）；查询为空时为 null，表示不做检索过滤。 */
    private val matchedIds: Flow<List<Long>?> = debouncedQuery.flatMapLatest { text ->
        if (text.isEmpty()) {
            flowOf(null)
        } else {
            flow<List<Long>> { emit(repo.searchIds(text)) }
        }
    }

    private val filters = combine(query, selectedTagIds, groupFilter, debouncedQuery) { q, tags, group, dq ->
        FilterState(query = q, tagIds = tags, group = group, debounced = dq)
    }

    val uiState: StateFlow<NoteListUiState> = combine(
        repo.observeNotes(),
        repo.observeTags(),
        repo.observeGroups(),
        filters,
        matchedIds,
    ) { allNotes, tags, groups, filter, ids ->
        val ranked: List<NoteWithTags> = if (ids == null) {
            allNotes
        } else {
            val order: Map<Long, Int> = ids.withIndex().associate { (index, id) -> id to index }
            allNotes.filter { it.note.id in order }.sortedBy { order[it.note.id] }
        }
        val byTag = if (filter.tagIds.isEmpty()) {
            ranked
        } else {
            ranked.filter { nw -> nw.tags.any { it.id in filter.tagIds } }
        }
        val visible = when (filter.group) {
            GROUP_ALL -> byTag
            GROUP_NONE -> byTag.filter { it.note.groupId == null }
            else -> byTag.filter { it.note.groupId == filter.group }
        }
        NoteListUiState(
            query = filter.query,
            highlightTerms = FtsText.highlightTerms(filter.debounced),
            notes = visible,
            tags = tags,
            groups = groups,
            selectedTagIds = filter.tagIds,
            groupFilter = filter.group,
            totalCount = allNotes.size,
            filtered = filter.debounced.isNotEmpty() || filter.tagIds.isNotEmpty() || filter.group != GROUP_ALL,
            loading = false,
            searchEngine = repo.searchEngineLabel,
            searchEngineIsFullText = repo.searchEngineIsFullText,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = NoteListUiState(),
    )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun clearQuery() {
        query.value = ""
    }

    fun toggleTagFilter(tagId: Long) {
        selectedTagIds.value = selectedTagIds.value.let { current ->
            if (tagId in current) current - tagId else current + tagId
        }
    }

    fun setGroupFilter(groupId: Long) {
        groupFilter.value = groupId
    }

    fun clearFilters() {
        selectedTagIds.value = emptySet()
        groupFilter.value = GROUP_ALL
    }

    fun deleteNote(noteId: Long) {
        viewModelScope.launch { repo.deleteNote(noteId) }
    }

    /** 配合删除后的撤销操作（软删除可恢复）。 */
    fun restoreNote(noteId: Long) {
        viewModelScope.launch { repo.restoreNote(noteId) }
    }

    private data class FilterState(
        val query: String,
        val tagIds: Set<Long>,
        val group: Long,
        val debounced: String,
    )
}
