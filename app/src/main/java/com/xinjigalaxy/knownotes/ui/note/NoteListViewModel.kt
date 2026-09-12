package com.xinjigalaxy.knownotes.ui.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xinjigalaxy.knownotes.data.fts.FtsText
import com.xinjigalaxy.knownotes.data.model.Group
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.data.model.SearchHistory
import com.xinjigalaxy.knownotes.data.model.Tag
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import com.xinjigalaxy.knownotes.data.search.SearchScope
import com.xinjigalaxy.knownotes.ui.NoteLayout
import com.xinjigalaxy.knownotes.ui.toNoteLayout
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
    /** 搜索历史建议（需求文档 3.4）。 */
    val searchHistory: List<SearchHistory> = emptyList(),
    /** 搜索框聚焦且内容为空时，才把历史铺开。 */
    val showSearchHistory: Boolean = false,
    /** 列表 / 瀑布流 */
    val layout: NoteLayout = NoteLayout.LIST,
    /** 检索范围（标题 / 正文 / 标签），默认全选。 */
    val searchScopes: Set<SearchScope> = SearchScope.ALL,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NoteListViewModel(
    private val repo: NoteRepository,
    private val prefs: UiPrefs,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedTagIds = MutableStateFlow(prefs.tagFilterOrNull() ?: emptySet())
    private val groupFilter = MutableStateFlow(prefs.groupFilterOrNull() ?: GROUP_ALL)
    private val searchFocused = MutableStateFlow(false)
    private val layout = MutableStateFlow(prefs.noteLayoutOrNull().toNoteLayout())
    private val searchScopes = MutableStateFlow(SearchScope.fromPrefs(prefs.searchScopesOrNull()))

    /** 输入即搜，防抖 300ms（需求文档 3.4）。 */
    private val debouncedQuery = query
        .debounce(300L)
        .map { it.trim() }
        .distinctUntilChanged()

    /** FTS5 命中的 id（按相关度排序）；查询为空时为 null，表示不做检索过滤。 */
    private val matchedIds: Flow<List<Long>?> =
        combine(debouncedQuery, searchScopes) { text, scopes -> text to scopes }
            .flatMapLatest { (text, scopes) ->
                if (text.isEmpty()) {
                    flowOf(null)
                } else {
                    flow<List<Long>> { emit(repo.searchIds(text, SearchScope.candidateLimit(scopes))) }
                }
            }

    private val criteria: Flow<FilterState> = combine(
        combine(query, selectedTagIds, groupFilter, debouncedQuery, searchScopes) { q, tagIds, group, debounced, scopes ->
            FilterState(query = q, tagIds = tagIds, group = group, debounced = debounced, scopes = scopes)
        },
        repo.observeRecentSearches(),
        searchFocused,
        layout,
    ) { base, history, focused, currentLayout ->
        base.copy(history = history, focused = focused, layout = currentLayout)
    }

    val uiState: StateFlow<NoteListUiState> = combine(
        repo.observeNotes(),
        repo.observeTags(),
        repo.observeGroups(),
        criteria,
        matchedIds,
    ) { allNotes, tags, groups, filter, ids ->
        val ranked: List<NoteWithTags> = if (ids == null) {
            allNotes
        } else {
            val order: Map<Long, Int> = ids.withIndex().associate { (index, id) -> id to index }
            allNotes.filter { it.note.id in order }.sortedBy { order[it.note.id] }
        }
        // 范围筛选放在结果侧：FTS 的列限定（`title:词`）属于"增强查询语法"，
        // Android 自带 SQLite 没编 SQLITE_ENABLE_FTS3_PARENTHESIS，真机上根本用不了；
        // 而 allNotes 本来就在内存里，过滤一次既可靠、又让 FTS / LIKE 两条路径语义一致。
        val scoped: List<NoteWithTags> =
            if (ids == null || filter.scopes.size == SearchScope.entries.size) {
                ranked
            } else {
                val terms = FtsText.highlightTerms(filter.debounced)
                ranked.filter { SearchScope.matches(it, terms, filter.scopes) }
            }
        val byTag = if (filter.tagIds.isEmpty()) {
            scoped
        } else {
            scoped.filter { nw -> nw.tags.any { it.id in filter.tagIds } }
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
            searchHistory = filter.history,
            showSearchHistory = filter.focused && filter.query.isBlank() && filter.history.isNotEmpty(),
            layout = filter.layout,
            searchScopes = filter.scopes,
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

    fun onSearchFocusChanged(focused: Boolean) {
        searchFocused.value = focused
    }

    /** 按下输入法「搜索」键时才写历史，避免边打字边污染历史。 */
    fun commitSearch() {
        val keyword = query.value.trim()
        if (keyword.isEmpty()) return
        viewModelScope.launch { repo.recordSearch(keyword) }
    }

    fun useHistoryKeyword(keyword: String) {
        query.value = keyword
        viewModelScope.launch { repo.recordSearch(keyword) }
    }

    fun deleteHistoryKeyword(keyword: String) {
        viewModelScope.launch { repo.deleteSearchKeyword(keyword) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { repo.clearSearchHistory() }
    }

    fun toggleTagFilter(tagId: Long) {
        selectedTagIds.value = selectedTagIds.value.let { current ->
            if (tagId in current) current - tagId else current + tagId
        }
        prefs.saveTagFilter(selectedTagIds.value)
    }

    fun setGroupFilter(groupId: Long) {
        groupFilter.value = groupId
        prefs.saveGroupFilter(groupId)
    }

    fun clearFilters() {
        selectedTagIds.value = emptySet()
        groupFilter.value = GROUP_ALL
        prefs.clearFilters()
    }

    /** 列表 ↔ 瀑布流循环切换，并记住选择。 */
    fun toggleLayout() {
        layout.value = layout.value.next()
        prefs.saveNoteLayout(layout.value.name)
    }

    /**
     * 切换检索范围。不允许全部取消 —— 一个都不选会一条都搜不到，
     * 与其让人对着空列表发呆，不如当成"重新全选"。
     */
    fun toggleSearchScope(scope: SearchScope) {
        val next = searchScopes.value.let { if (scope in it) it - scope else it + scope }
        searchScopes.value = next.ifEmpty { SearchScope.ALL }
        prefs.saveSearchScopes(SearchScope.toPrefs(searchScopes.value))
    }

    fun selectAllSearchScopes() {
        searchScopes.value = SearchScope.ALL
        prefs.saveSearchScopes(SearchScope.toPrefs(SearchScope.ALL))
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
        val scopes: Set<SearchScope> = SearchScope.ALL,
        val history: List<SearchHistory> = emptyList(),
        val focused: Boolean = false,
        val layout: NoteLayout = NoteLayout.LIST,
    )
}
