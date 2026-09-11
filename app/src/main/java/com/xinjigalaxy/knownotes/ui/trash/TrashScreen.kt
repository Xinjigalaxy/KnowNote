package com.xinjigalaxy.knownotes.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xinjigalaxy.knownotes.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.components.EmptyHint
import com.xinjigalaxy.knownotes.ui.components.formatTime
import kotlinx.coroutines.launch

/**
 * 回收站（需求文档 5.1 的界面清单里没有，但软删除字段是文档要求的，
 * 没有入口的软删除等于变相丢数据，所以补上）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var purgeTarget by remember { mutableStateOf<NoteWithTags?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.trash)) },
                actions = {
                    if (notes.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) { Text(stringResource(R.string.clear)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (notes.isEmpty()) {
            EmptyHint(
                icon = Icons.Outlined.DeleteOutline,
                title = stringResource(R.string.trash_is_empty),
                subtitle = stringResource(R.string.notes_deleted_from_the_list_land_here_first_and_),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(notes, key = { it.note.id }) { item ->
                    TrashCard(
                        item = item,
                        onRestore = {
                            viewModel.restore(item.note.id)
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.restored_to_the_notes_list)) }
                        },
                        onPurge = { purgeTarget = item },
                    )
                }
            }
        }
    }

    purgeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text(stringResource(R.string.delete_this_note_permanently)) },
            text = {
                Text(
                    stringResource(R.string.s_1_s_will_disappear_from_the_list_and_search_pe, target.note.title.ifBlank { stringResource(R.string.untitled_note) }) +
                        stringResource(R.string.the_deletion_syncs_to_your_other_devices)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.purge(target.note.id)
                    purgeTarget = null
                }) { Text(stringResource(R.string.delete_permanently)) }
            },
            dismissButton = { TextButton(onClick = { purgeTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(stringResource(R.string.empty_the_trash)) },
            text = {
                Text(
                    stringResource(R.string.the_current_notes_size_notes_will_disappear_from, notes.size) +
                        stringResource(R.string.the_deletion_syncs_to_your_other_devices)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    viewModel.purgeAll { removed ->
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.permanently_deleted_removed, removed)) }
                    }
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TrashCard(
    item: NoteWithTags,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.note.title.ifBlank { stringResource(R.string.untitled_note) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.note.content.isNotBlank()) {
                    Text(
                        text = item.note.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.deleted_formattime_item_note_updatedat, formatTime(item.note.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onRestore) {
                Icon(Icons.Outlined.Restore, contentDescription = stringResource(R.string.restore))
            }
            IconButton(onClick = onPurge) {
                Icon(
                    Icons.Outlined.DeleteForever,
                    contentDescription = stringResource(R.string.delete_permanently),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
