package com.xinjigalaxy.knownotes.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.components.formatFullTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteEditScreen(
    noteId: Long?,
    onDone: () -> Unit,
    viewModel: NoteEditViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    LaunchedEffect(noteId) { viewModel.load(noteId) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val existingTags by viewModel.allTags.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    var tagInput by remember { mutableStateOf("") }
    var newGroupName by remember { mutableStateOf("") }
    var showNewGroup by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 返回键 = 保存后退出，避免误退丢内容
    BackHandler(enabled = true) { viewModel.saveOnExit(onDone) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.saveOnExit(onDone) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text(if (state.isNew) "新建知识点" else "编辑知识点") },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除")
                        }
                    }
                    TextButton(onClick = { viewModel.save { onDone() } }) { Text("保存") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::setTitle,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标题") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            OutlinedTextField(
                value = state.content,
                onValueChange = viewModel::setContent,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
                label = { Text("正文") },
                placeholder = { Text("零碎知识点随手记，支持代码片段原样粘贴") },
            )

            HorizontalDivider()

            Text("分组", style = MaterialTheme.typography.titleSmall)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.groupId == null,
                        onClick = { viewModel.setGroup(null) },
                        label = { Text("无分组") },
                    )
                }
                items(groups, key = { it.id }) { group ->
                    FilterChip(
                        selected = state.groupId == group.id,
                        onClick = {
                            viewModel.setGroup(if (state.groupId == group.id) null else group.id)
                        },
                        label = { Text(group.name) },
                    )
                }
                item {
                    AssistChip(
                        onClick = { showNewGroup = true },
                        label = { Text("＋ 新建分组") },
                    )
                }
            }

            Text("标签", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val names = (existingTags.map { it.name } + state.tags).distinct().sorted()
                if (names.isEmpty()) {
                    Text(
                        text = "还没有标签，下面输入即可新建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                names.forEach { name ->
                    FilterChip(
                        selected = name in state.tags,
                        onClick = { viewModel.toggleTag(name) },
                        label = { Text(name) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("添加标签") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            viewModel.addTag(tagInput)
                            tagInput = ""
                        },
                    ),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        viewModel.addTag(tagInput)
                        tagInput = ""
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加标签")
                }
            }

            if (!state.isNew) {
                Text(
                    text = "创建于 ${formatFullTime(state.createdAt)} · 更新于 ${formatFullTime(state.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }

    if (showNewGroup) {
        AlertDialog(
            onDismissRequest = { showNewGroup = false },
            title = { Text("新建分组") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("分组名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createGroup(newGroupName)
                    newGroupName = ""
                    showNewGroup = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewGroup = false }) { Text("取消") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条知识点？") },
            text = { Text("笔记会进入软删除状态，数据仍在库里，可随时恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onDone)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
