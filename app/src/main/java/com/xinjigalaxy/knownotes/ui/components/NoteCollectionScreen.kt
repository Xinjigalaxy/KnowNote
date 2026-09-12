package com.xinjigalaxy.knownotes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xinjigalaxy.knownotes.R
import com.xinjigalaxy.knownotes.data.markup.InlineMarkup
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.ui.NoteLayout

/**
 * 「某个分组 / 某个标签下的笔记」二级页面。
 *
 * 版式刻意和笔记页保持一致：常驻筛选框 + 状态行 + 列表 ↔ 瀑布流循环切换 + 点击查看 / 长按编辑，
 * 差别只是数据范围被限定在某个分组或标签里。左上角返回回到上级列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteCollectionScreen(
    title: String,
    notes: List<NoteWithTags>,
    layout: NoteLayout,
    filterHint: String,
    onToggleLayout: () -> Unit,
    onOpenNote: (Long, Boolean) -> Unit,
    onBack: () -> Unit,
    groupNames: Map<Long, String> = emptyMap(),
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(notes, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            notes
        } else {
            notes.filter { item ->
                item.note.title.lowercase().contains(q) ||
                    InlineMarkup.plain(item.note.content).lowercase().contains(q) ||
                    item.tags.any { it.name.lowercase().contains(q) }
            }
        }
    }

    // 笔记跨多个分组时才显示分组名（在分组页里显示是废话）
    val distinctGroups = notes.map { it.note.groupId }.distinct()
    val showGroupName = distinctGroups.size > 1

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { LayoutToggleButton(layout = layout, onToggle = onToggleLayout) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(filterHint) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear))
                        }
                    }
                },
                singleLine = true,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (query.isBlank()) {
                        stringResource(R.string.notes_size_notes, notes.size)
                    } else {
                        stringResource(R.string.filtered_size_notes_size_shown, filtered.size, notes.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (layout == NoteLayout.STAGGERED) stringResource(R.string.grid) else stringResource(R.string.list),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (filtered.isEmpty()) {
                EmptyHint(
                    icon = Icons.AutoMirrored.Outlined.StickyNote2,
                    title = if (query.isBlank()) stringResource(R.string.no_notes_here_yet) else stringResource(R.string.no_matching_notes),
                    subtitle = if (query.isBlank()) {
                        stringResource(R.string.assign_the_note_to_this_group_add_this_tag_on_th)
                    } else {
                        stringResource(R.string.try_another_keyword)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (layout == NoteLayout.STAGGERED) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp,
                ) {
                    items(filtered, key = { it.note.id }) { item ->
                        StaggeredNoteCard(
                            item = item,
                            terms = emptyList(),
                            groupName = if (showGroupName) groupNames[item.note.groupId] else null,
                            onClick = { onOpenNote(item.note.id, true) },
                            onLongClick = { onOpenNote(item.note.id, false) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.note.id }) { item ->
                        NoteCard(
                            item = item,
                            terms = emptyList(),
                            groupName = if (showGroupName) groupNames[item.note.groupId] else null,
                            onClick = { onOpenNote(item.note.id, true) },
                            onLongClick = { onOpenNote(item.note.id, false) },
                        )
                    }
                }
            }
        }
    }
}
