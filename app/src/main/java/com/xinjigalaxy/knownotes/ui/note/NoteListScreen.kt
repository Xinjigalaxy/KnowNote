package com.xinjigalaxy.knownotes.ui.note

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xinjigalaxy.knownotes.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.data.search.SearchScope
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.NoteLayout
import com.xinjigalaxy.knownotes.ui.components.EmptyHint
import com.xinjigalaxy.knownotes.ui.components.HighlightedText
import com.xinjigalaxy.knownotes.ui.components.LayoutToggleButton
import com.xinjigalaxy.knownotes.ui.components.NoteCard
import com.xinjigalaxy.knownotes.ui.components.StaggeredNoteCard
import com.xinjigalaxy.knownotes.ui.components.formatTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NoteListScreen(
    /** @param 第二参数 preview：true = 查看（预览渲染），false = 直接编辑 */
    onOpenNote: (Long, Boolean) -> Unit,
    onCreateNote: () -> Unit,
    viewModel: NoteListViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var groupMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.notes_2)) },
                    actions = {
                        // 列表 ↔ 瀑布流 循环切换
                        LayoutToggleButton(layout = state.layout, onToggle = viewModel::toggleLayout)
                        Box {
                            IconButton(onClick = { groupMenuOpen = true }) {
                                Icon(Icons.Outlined.FilterAlt, contentDescription = stringResource(R.string.filter_by_group))
                            }
                            DropdownMenu(
                                expanded = groupMenuOpen,
                                onDismissRequest = { groupMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.all_groups)) },
                                    onClick = { viewModel.setGroupFilter(GROUP_ALL); groupMenuOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ungrouped)) },
                                    onClick = { viewModel.setGroupFilter(GROUP_NONE); groupMenuOpen = false },
                                )
                                if (state.groups.isNotEmpty()) HorizontalDivider()
                                state.groups.forEach { group ->
                                    DropdownMenuItem(
                                        text = { Text(group.name) },
                                        onClick = { viewModel.setGroupFilter(group.id); groupMenuOpen = false },
                                    )
                                }
                            }
                        }
                    },
                )

                // 常驻搜索框：输入即搜（防抖 300ms）；按输入法搜索键才写历史
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .onFocusChanged { viewModel.onSearchFocusChanged(it.isFocused) },
                    placeholder = { Text(stringResource(R.string.search_title_body_tags)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearQuery) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.commitSearch() }),
                )

                // 检索范围（标题 / 正文 / 标签）：只在开始检索后出现，默认全选
                if (state.query.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        item {
                            Text(
                                text = stringResource(R.string.search_scope),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        SearchScope.entries.forEach { scope ->
                            item(key = scope.name) {
                                FilterChip(
                                    selected = scope in state.searchScopes,
                                    onClick = { viewModel.toggleSearchScope(scope) },
                                    label = { Text(stringResource(scope.labelRes)) },
                                )
                            }
                        }
                    }
                }

                if (state.showSearchHistory) {
                    SearchHistoryPanel(
                        state = state,
                        onUse = viewModel::useHistoryKeyword,
                        onDelete = viewModel::deleteHistoryKeyword,
                        onClearAll = viewModel::clearSearchHistory,
                    )
                }

                if (state.tags.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        items(state.tags, key = { it.id }) { tag ->
                            FilterChip(
                                selected = tag.id in state.selectedTagIds,
                                onClick = { viewModel.toggleTagFilter(tag.id) },
                                label = { Text(tag.name) },
                            )
                        }
                    }
                }

                StatusLine(
                    filtered = state.filtered,
                    shown = state.notes.size,
                    total = state.totalCount,
                    searchEngine = state.searchEngine,
                    searchEngineIsFullText = state.searchEngineIsFullText,
                    onClear = viewModel::clearFilters,
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateNote,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_note_2)) },
            )
        },
    ) { innerPadding ->
        if (state.notes.isEmpty() && !state.loading) {
            EmptyHint(
                icon = Icons.Outlined.StickyNote2,
                title = if (state.filtered) stringResource(R.string.no_matching_notes_2) else stringResource(R.string.no_records_yet),
                subtitle = if (state.filtered) stringResource(R.string.try_another_keyword_or_clear_the_filters_in_the_) else stringResource(R.string.tap_new_note_at_the_bottom_right_to_jot_it_down),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else if (state.layout == NoteLayout.STAGGERED) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 10.dp,
            ) {
                staggeredItems(state.notes, key = { it.note.id }) { item ->
                    StaggeredNoteCard(
                        item = item,
                        terms = state.highlightTerms,
                        groupName = state.groups.firstOrNull { it.id == item.note.groupId }?.name,
                        onClick = { onOpenNote(item.note.id, true) },
                        onLongClick = { onOpenNote(item.note.id, false) },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.notes, key = { it.note.id }) { item ->
                    NoteCard(
                        item = item,
                        terms = state.highlightTerms,
                        groupName = state.groups.firstOrNull { it.id == item.note.groupId }?.name,
                        // 点击 = 查看（预览），长按 = 编辑
                        onClick = { onOpenNote(item.note.id, true) },
                        onLongClick = { onOpenNote(item.note.id, false) },
                    )
                }
            }
        }
    }
}

/** 瀑布流卡片：紧凑、高度随内容自然变化，才形成错落效果。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StaggeredNoteCard(
    item: NoteWithTags,
    terms: List<String>,
    groupName: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HighlightedText(
                text = item.note.title.ifBlank { stringResource(R.string.untitled_note) },
                terms = terms,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 3,
            )
            if (item.note.content.isNotBlank()) {
                HighlightedText(
                    text = item.note.content,
                    terms = terms,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 8,
                )
            }
            groupName?.let {
                Text(
                    text = "▸ $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (item.tags.isNotEmpty()) {
                Text(
                    text = item.tags.joinToString(" ") { "#${it.name}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatTime(item.note.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 搜索历史（需求文档 3.4）：点即用，X 删单条，右侧可清空。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistoryPanel(
    state: NoteListUiState,
    onUse: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.searchHistory.forEach { entry ->
            InputChip(
                selected = false,
                onClick = { onUse(entry.keyword) },
                label = { Text(entry.keyword) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.delete_this_history_entry),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onDelete(entry.keyword) },
                    )
                },
            )
        }
        AssistChip(
            onClick = onClearAll,
            label = { Text(stringResource(R.string.clear_history)) },
        )
    }
}

@Composable
private fun StatusLine(
    filtered: Boolean,
    shown: Int,
    total: Int,
    searchEngine: String,
    searchEngineIsFullText: Boolean,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (filtered) stringResource(R.string.showing_shown_of_total, shown, total) else stringResource(R.string.total_total, total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (searchEngineIsFullText) stringResource(R.string.searchengine_full_text_index, searchEngine) else "· $searchEngine",
            style = MaterialTheme.typography.labelMedium,
            color = if (searchEngineIsFullText) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Spacer(Modifier.weight(1f))
        if (filtered) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.clear_filters)) }
        }
    }
}
