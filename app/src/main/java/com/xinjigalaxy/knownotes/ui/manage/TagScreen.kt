package com.xinjigalaxy.knownotes.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.data.model.Tag
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.NoteLayout
import com.xinjigalaxy.knownotes.ui.components.EmptyHint
import com.xinjigalaxy.knownotes.ui.components.LayoutToggleButton
import com.xinjigalaxy.knownotes.ui.toNoteLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagViewModel(
    private val repo: NoteRepository,
    private val prefs: UiPrefs,
) : ViewModel() {

    data class Row(val tag: Tag, val noteCount: Int)

    data class UiState(
        val rows: List<Row> = emptyList(),
        val layout: NoteLayout = NoteLayout.LIST,
    )

    private val layout = MutableStateFlow(prefs.noteLayoutOrNull().toNoteLayout())

    val uiState: StateFlow<UiState> = combine(
        repo.observeTags(),
        repo.observeNotes(),
        layout,
    ) { tags, notes, currentLayout ->
        val counts = buildMap<Long, Int> {
            notes.forEach { item -> item.tags.forEach { tag -> put(tag.id, (this[tag.id] ?: 0) + 1) } }
        }
        UiState(
            rows = tags.map { Row(it, counts[it.id] ?: 0) },
            layout = currentLayout,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState())

    fun toggleLayout() {
        layout.value = layout.value.next()
        prefs.saveNoteLayout(layout.value.name)
    }

    fun create(name: String) = viewModelScope.launch { repo.createTag(name) }

    fun rename(id: Long, name: String) = viewModelScope.launch { repo.renameTag(id, name) }

    fun merge(fromId: Long, toId: Long) = viewModelScope.launch { repo.mergeTags(fromId, toId) }

    fun delete(id: Long) = viewModelScope.launch { repo.deleteTag(id) }
}

/**
 * 标签列表页。点条目 → 打开「该标签下的笔记」二级页面（左上返回回到这里）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    onOpenTag: (Long) -> Unit,
    viewModel: TagViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showNewDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Tag?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var mergeSource by remember { mutableStateOf<Tag?>(null) }
    var deleteTarget by remember { mutableStateOf<Tag?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("标签") },
                actions = {
                    LayoutToggleButton(layout = state.layout, onToggle = viewModel::toggleLayout)
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newName = ""; showNewDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建标签") },
            )
        },
    ) { innerPadding ->
        if (state.rows.isEmpty()) {
            EmptyHint(
                icon = Icons.Outlined.Sell,
                title = "还没有标签",
                subtitle = "标签用来做跨分组的横向归类，例如「待复习」「面试高频」",
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
                items(state.rows, key = { it.tag.id }) { row ->
                    TagTile(
                        row = row,
                        onOpen = { onOpenTag(row.tag.id) },
                        onRename = { renameTarget = row.tag; renameValue = row.tag.name },
                        onMerge = { mergeSource = row.tag },
                        onDelete = { deleteTarget = row.tag },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.rows, key = { it.tag.id }) { row ->
                    TagRowCard(
                        row = row,
                        onOpen = { onOpenTag(row.tag.id) },
                        onRename = { renameTarget = row.tag; renameValue = row.tag.name },
                        onMerge = { mergeSource = row.tag },
                        onDelete = { deleteTarget = row.tag },
                    )
                }
            }
        }
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("新建标签") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("标签名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.create(newName)
                    showNewDialog = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text("取消") } },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名标签") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("标签名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(target.id, renameValue)
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    mergeSource?.let { source ->
        val candidates = state.rows.map { it.tag }.filter { it.id != source.id }
        AlertDialog(
            onDismissRequest = { mergeSource = null },
            title = { Text("把「${source.name}」合并到…") },
            text = {
                if (candidates.isEmpty()) {
                    Text("至少要有两个标签才能合并。")
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        candidates.forEach { candidate ->
                            TextButton(
                                onClick = {
                                    viewModel.merge(source.id, candidate.id)
                                    mergeSource = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("合并到「${candidate.name}」", modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mergeSource = null }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除标签「${target.name}」") },
            text = { Text("只会解除标签与笔记的关联，不会删除笔记本身。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TagMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("重命名") }, onClick = { onDismiss(); onRename() })
        DropdownMenuItem(text = { Text("合并到…") }, onClick = { onDismiss(); onMerge() })
        DropdownMenuItem(text = { Text("删除") }, onClick = { onDismiss(); onDelete() })
    }
}

@Composable
private fun TagRowCard(
    row: TagViewModel.Row,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "#${row.tag.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${row.noteCount} 条笔记",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                }
                TagMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onRename = onRename,
                    onMerge = onMerge,
                    onDelete = onDelete,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "打开",
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun TagTile(
    row: TagViewModel.Row,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${row.tag.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "更多操作",
                            modifier = Modifier.width(20.dp),
                        )
                    }
                    TagMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        onRename = onRename,
                        onMerge = onMerge,
                        onDelete = onDelete,
                    )
                }
            }
            Text(
                text = "${row.noteCount} 条笔记",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "查看笔记",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp),
                )
            }
        }
    }
}
