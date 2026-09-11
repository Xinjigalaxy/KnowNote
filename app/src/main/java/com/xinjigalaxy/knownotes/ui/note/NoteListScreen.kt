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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StickyNote2
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.components.EmptyHint
import com.xinjigalaxy.knownotes.ui.components.HighlightedText
import com.xinjigalaxy.knownotes.ui.components.formatTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    onOpenNote: (Long) -> Unit,
    onCreateNote: () -> Unit,
    viewModel: NoteListViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingNote by remember { mutableStateOf<NoteWithTags?>(null) }
    var groupMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("知识点") },
                    actions = {
                        Box {
                            IconButton(onClick = { groupMenuOpen = true }) {
                                Icon(Icons.Outlined.FilterAlt, contentDescription = "按分组筛选")
                            }
                            DropdownMenu(
                                expanded = groupMenuOpen,
                                onDismissRequest = { groupMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("全部分组") },
                                    onClick = { viewModel.setGroupFilter(GROUP_ALL); groupMenuOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("未分组") },
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
                    placeholder = { Text("搜索标题 / 正文 / 标签") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearQuery) {
                                Icon(Icons.Outlined.Close, contentDescription = "清空")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.commitSearch() }),
                )

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
                text = { Text("记一条") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.notes.isEmpty() && !state.loading) {
            EmptyHint(
                icon = Icons.Outlined.StickyNote2,
                title = if (state.filtered) "没有匹配的知识点" else "还没有记录",
                subtitle = if (state.filtered) "换个关键词，或点右上角清空筛选" else "点右下角「记一条」，先把碎片记下来",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
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
                        onClick = { onOpenNote(item.note.id) },
                        onLongClick = { pendingNote = item },
                    )
                }
            }
        }
    }

    pendingNote?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingNote = null },
            title = { Text(note.note.title.ifBlank { "未命名笔记" }) },
            text = { Text("要对这条知识点做什么？") },
            confirmButton = {
                TextButton(onClick = {
                    val id = note.note.id
                    pendingNote = null
                    onOpenNote(id)
                }) { Text("编辑") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val id = note.note.id
                    pendingNote = null
                    viewModel.deleteNote(id)
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "已删除",
                            actionLabel = "撤销",
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.restoreNote(id)
                    }
                }) { Text("删除") }
            },
        )
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
                        contentDescription = "删除这条历史",
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onDelete(entry.keyword) },
                    )
                },
            )
        }
        AssistChip(
            onClick = onClearAll,
            label = { Text("清空历史") },
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
            text = if (filtered) "筛出 $shown / 共 $total 条" else "共 $total 条",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (searchEngineIsFullText) "· $searchEngine 全文索引" else "· $searchEngine",
            style = MaterialTheme.typography.labelMedium,
            color = if (searchEngineIsFullText) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Spacer(Modifier.weight(1f))
        if (filtered) {
            TextButton(onClick = onClear) { Text("清空筛选") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HighlightedText(
                text = item.note.title.ifBlank { "未命名笔记" },
                terms = terms,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            if (item.note.content.isNotBlank()) {
                HighlightedText(
                    text = item.note.content,
                    terms = terms,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 3,
                )
            }
            if (item.tags.isNotEmpty() || groupName != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    groupName?.let {
                        Text(
                            text = "▸ $it",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    item.tags.take(4).forEach { tag ->
                        AssistChip(
                            onClick = { },
                            label = { Text("#${tag.name}", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                    if (item.tags.size > 4) {
                        Text(
                            text = "+${item.tags.size - 4}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            Text(
                text = formatTime(item.note.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(0.dp))
        }
    }
}
