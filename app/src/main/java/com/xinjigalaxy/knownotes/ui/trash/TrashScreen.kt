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
    var purgeTarget by remember { mutableStateOf<NoteWithTags?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("回收站") },
                actions = {
                    if (notes.isNotEmpty()) {
                        TextButton(onClick = { confirmEmpty = true }) { Text("清空") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (notes.isEmpty()) {
            EmptyHint(
                icon = Icons.Outlined.DeleteOutline,
                title = "回收站是空的",
                subtitle = "列表里长按删除的笔记会先落到这里，可以随时恢复",
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
                            scope.launch { snackbarHostState.showSnackbar("已恢复到笔记列表") }
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
            title = { Text("彻底删除这条笔记？") },
            text = {
                Text(
                    "「${target.note.title.ifBlank { "未命名笔记" }}」会从列表与检索里永久消失，本机无法恢复；" +
                        "删除状态会同步给其他设备。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.purge(target.note.id)
                    purgeTarget = null
                }) { Text("彻底删除") }
            },
            dismissButton = { TextButton(onClick = { purgeTarget = null }) { Text("取消") } },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("清空回收站？") },
            text = {
                Text(
                    "当前 ${notes.size} 条笔记会从列表与检索里永久消失，本机无法恢复；" +
                        "删除状态会同步给其他设备。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    viewModel.purgeAll { removed ->
                        scope.launch { snackbarHostState.showSnackbar("已永久删除 $removed 条") }
                    }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("取消") } },
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
                    text = item.note.title.ifBlank { "未命名笔记" },
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
                    text = "删除于 ${formatTime(item.note.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onRestore) {
                Icon(Icons.Outlined.Restore, contentDescription = "恢复")
            }
            IconButton(onClick = onPurge) {
                Icon(
                    Icons.Outlined.DeleteForever,
                    contentDescription = "彻底删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
