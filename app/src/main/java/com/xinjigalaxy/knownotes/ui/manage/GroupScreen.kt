package com.xinjigalaxy.knownotes.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.data.model.Group
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.NoteLayout
import com.xinjigalaxy.knownotes.ui.components.EmptyHint
import com.xinjigalaxy.knownotes.ui.components.LayoutToggleButton
import com.xinjigalaxy.knownotes.ui.components.NoteCard
import com.xinjigalaxy.knownotes.ui.toNoteLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupViewModel(
    private val repo: NoteRepository,
    private val prefs: UiPrefs,
) : ViewModel() {

    /** 行里直接带上组内笔记，「点击展开」就不用再查一次库。 */
    data class Row(val group: Group, val notes: List<NoteWithTags>) {
        val noteCount: Int get() = notes.size
    }

    data class UiState(
        val rows: List<Row> = emptyList(),
        val layout: NoteLayout = NoteLayout.LIST,
    )

    private val layout = MutableStateFlow(prefs.noteLayoutOrNull().toNoteLayout())

    val uiState: StateFlow<UiState> = combine(
        repo.observeGroups(),
        repo.observeNotes(),
        layout,
    ) { groups, notes, currentLayout ->
        val byGroup = notes.groupBy { it.note.groupId }
        UiState(
            rows = groups.map { Row(it, byGroup[it.id].orEmpty()) },
            layout = currentLayout,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState())

    fun toggleLayout() {
        layout.value = layout.value.next()
        prefs.saveNoteLayout(layout.value.name)
    }

    fun create(name: String) = viewModelScope.launch { repo.createGroup(name) }

    fun rename(id: Long, name: String) = viewModelScope.launch { repo.renameGroup(id, name) }

    fun move(id: Long, delta: Int) = viewModelScope.launch { repo.moveGroup(id, delta) }

    fun delete(id: Long, keepNotes: Boolean) = viewModelScope.launch { repo.deleteGroup(id, keepNotes) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    onOpenNote: (Long, Boolean) -> Unit,
    viewModel: GroupViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 展开的分组 id（仅界面状态）
    var expanded by remember { mutableStateOf(emptySet<Long>()) }
    fun toggleExpand(id: Long) {
        expanded = if (id in expanded) expanded - id else expanded + id
    }

    var showNewDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Group?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Group?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("分组") },
                actions = {
                    LayoutToggleButton(layout = state.layout, onToggle = viewModel::toggleLayout)
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newName = ""; showNewDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建分组") },
            )
        },
    ) { innerPadding ->
        if (state.rows.isEmpty()) {
            EmptyHint(
                icon = Icons.Outlined.Folder,
                title = "还没有分组",
                subtitle = "分组用来把知识点按主题归档，例如「Java 并发」「SQL 索引」",
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
                state.rows.forEach { row ->
                    item(key = "g${row.group.id}") {
                        GroupTile(
                            row = row,
                            expanded = row.group.id in expanded,
                            onToggle = { toggleExpand(row.group.id) },
                            onRename = { renameTarget = row.group; renameValue = row.group.name },
                            onMoveUp = { viewModel.move(row.group.id, -1) },
                            onMoveDown = { viewModel.move(row.group.id, 1) },
                            onDelete = { deleteTarget = row.group },
                        )
                    }
                    if (row.group.id in expanded) {
                        item(key = "gn${row.group.id}", span = StaggeredGridItemSpan.FullLine) {
                            ExpandedNotes(row.notes, onOpenNote)
                        }
                    }
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
                state.rows.forEach { row ->
                    item(key = "g${row.group.id}") {
                        GroupRowCard(
                            row = row,
                            expanded = row.group.id in expanded,
                            onToggle = { toggleExpand(row.group.id) },
                            onRename = { renameTarget = row.group; renameValue = row.group.name },
                            onMoveUp = { viewModel.move(row.group.id, -1) },
                            onMoveDown = { viewModel.move(row.group.id, 1) },
                            onDelete = { deleteTarget = row.group },
                        )
                    }
                    if (row.group.id in expanded) {
                        item(key = "gn${row.group.id}") {
                            ExpandedNotes(row.notes, onOpenNote)
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("新建分组") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("分组名") },
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
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("分组名") },
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

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除分组「${target.name}」") },
            text = { Text("组内笔记要一起删除吗？「保留笔记」会把它们移到「无分组」。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id, keepNotes = true)
                    deleteTarget = null
                }) { Text("保留笔记") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id, keepNotes = false)
                    deleteTarget = null
                }) { Text("一起删除") }
            },
        )
    }
}

/** 展开后的组内笔记：直接复用笔记页的卡片与交互（点击查看 / 长按编辑）。 */
@Composable
private fun ExpandedNotes(
    notes: List<NoteWithTags>,
    onOpenNote: (Long, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (notes.isEmpty()) {
            Text(
                text = "这个分组下还没有笔记，长按笔记可在编辑页里选分组",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            return
        }
        notes.forEach { item ->
            NoteCard(
                item = item,
                terms = emptyList(),
                groupName = null,
                onClick = { onOpenNote(item.note.id, true) },
                onLongClick = { onOpenNote(item.note.id, false) },
            )
        }
    }
}

@Composable
private fun GroupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("重命名") }, onClick = { onDismiss(); onRename() })
        DropdownMenuItem(text = { Text("上移") }, onClick = { onDismiss(); onMoveUp() })
        DropdownMenuItem(text = { Text("下移") }, onClick = { onDismiss(); onMoveDown() })
        DropdownMenuItem(text = { Text("删除") }, onClick = { onDismiss(); onDelete() })
    }
}

@Composable
private fun GroupRowCard(
    row: GroupViewModel.Row,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.group.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${row.noteCount} 条笔记 · 点条目展开查看",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                }
                GroupMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onRename = onRename,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun GroupTile(
    row: GroupViewModel.Row,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.group.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
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
                    GroupMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        onRename = onRename,
                        onMoveUp = onMoveUp,
                        onMoveDown = onMoveDown,
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
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
