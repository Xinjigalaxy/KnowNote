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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
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

class GroupViewModel(
    private val repo: NoteRepository,
    private val prefs: UiPrefs,
) : ViewModel() {

    data class Row(val group: Group, val noteCount: Int)

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
        val counts = notes.groupingBy { it.note.groupId }.eachCount()
        UiState(
            rows = groups.map { Row(it, counts[it.id] ?: 0) },
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

/**
 * 分组列表页。点条目 → 打开「该分组下的笔记」二级页面（左上返回回到这里）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    onOpenGroup: (Long) -> Unit,
    viewModel: GroupViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
                items(state.rows, key = { it.group.id }) { row ->
                    GroupTile(
                        row = row,
                        onOpen = { onOpenGroup(row.group.id) },
                        onRename = { renameTarget = row.group; renameValue = row.group.name },
                        onMoveUp = { viewModel.move(row.group.id, -1) },
                        onMoveDown = { viewModel.move(row.group.id, 1) },
                        onDelete = { deleteTarget = row.group },
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
                items(state.rows, key = { it.group.id }) { row ->
                    GroupRowCard(
                        row = row,
                        onOpen = { onOpenGroup(row.group.id) },
                        onRename = { renameTarget = row.group; renameValue = row.group.name },
                        onMoveUp = { viewModel.move(row.group.id, -1) },
                        onMoveDown = { viewModel.move(row.group.id, 1) },
                        onDelete = { deleteTarget = row.group },
                    )
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
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
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
                    text = row.group.name,
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
                GroupMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onRename = onRename,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
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
private fun GroupTile(
    row: GroupViewModel.Row,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
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
