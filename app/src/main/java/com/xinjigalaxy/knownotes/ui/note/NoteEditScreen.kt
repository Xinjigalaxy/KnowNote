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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.xinjigalaxy.knownotes.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.components.formatFullTime
import com.xinjigalaxy.knownotes.ui.markdown.MarkdownText

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteEditScreen(
    noteId: Long?,
    /** true = 以「查看」模式打开（列表里点击进入），false = 直接编辑（长按进入） */
    openInPreview: Boolean,
    onDone: () -> Unit,
    viewModel: NoteEditViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    LaunchedEffect(noteId) { viewModel.load(noteId, openInPreview) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val existingTags by viewModel.allTags.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    var tagInput by remember { mutableStateOf("") }
    var newGroupName by remember { mutableStateOf("") }
    var showNewGroup by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 返回键 = 保存后退出，避免误退丢内容（输入框里没点确认的标签也一起带上）
    BackHandler(enabled = true) { viewModel.saveOnExit(tagInput, onDone) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.saveOnExit(tagInput, onDone) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(if (state.isNew) stringResource(R.string.new_note) else stringResource(R.string.edit_note)) },
                actions = {
                    IconButton(onClick = { viewModel.setPreview(!state.preview) }) {
                        Icon(
                            imageVector = if (state.preview) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                            contentDescription = if (state.preview) stringResource(R.string.switch_to_edit) else stringResource(R.string.preview_markdown),
                        )
                    }
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    TextButton(onClick = { viewModel.save(tagInput) { onDone() } }) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { innerPadding ->
        // 预览模式：渲染后的只读视图（Markdown 渲染见 MarkdownText）
        if (state.preview) {
            NotePreviewBody(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            return@Scaffold
        }

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
                label = { Text(stringResource(R.string.title)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            OutlinedTextField(
                value = state.content,
                onValueChange = viewModel::setContent,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
                label = { Text(stringResource(R.string.body)) },
                placeholder = { Text(stringResource(R.string.jot_fragmented_notes_anytime_code_snippets_paste)) },
            )

            HorizontalDivider()

            Text(stringResource(R.string.groups), style = MaterialTheme.typography.titleSmall)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                item {
                    FilterChip(
                        selected = state.groupId == null,
                        onClick = { viewModel.setGroup(null) },
                        label = { Text(stringResource(R.string.no_group)) },
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
                        label = { Text(stringResource(R.string.new_group_2)) },
                    )
                }
            }

            Text(stringResource(R.string.tags), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val names = (existingTags.map { it.name } + state.tags).distinct().sorted()
                if (names.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_tags_yet_type_below_to_create_one),
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
                // 输入框里还没点确认的标签，直接摆成一个可点的 chip，
                // 让"打一半就去点保存"也能把标签存下来（v1.1.0 的实际 bug）
                val pending = tagInput.trim()
                if (pending.isNotEmpty() && pending !in names) {
                    AssistChip(
                        onClick = {
                            viewModel.addTag(pending)
                            tagInput = ""
                        },
                        label = { Text(stringResource(R.string.new_pending, pending)) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.add_tag)) },
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
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_tag))
                }
            }

            if (!state.isNew) {
                Text(
                    text = stringResource(R.string.created_formatfulltime_state_createdat_updated_f, formatFullTime(state.createdAt), formatFullTime(state.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }

    if (showNewGroup) {
        AlertDialog(
            onDismissRequest = { showNewGroup = false },
            title = { Text(stringResource(R.string.new_group)) },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createGroup(newGroupName)
                    newGroupName = ""
                    showNewGroup = false
                }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { showNewGroup = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_this_note)) },
            text = { Text(stringResource(R.string.the_note_goes_to_trash_soft_delete_and_can_be_re)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onDone)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 只读预览：标题 + 渲染后的正文 + 标签。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotePreviewBody(state: NoteEditViewModel.State, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.title.isNotBlank()) {
            Text(text = state.title, style = MaterialTheme.typography.headlineSmall)
        }
        if (state.content.isBlank()) {
            Text(
                text = stringResource(R.string.body_is_still_empty_tap_the_top_right_to_switch_),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            MarkdownText(text = state.content, modifier = Modifier.fillMaxWidth())
        }
        if (state.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.tags.forEach { name ->
                    AssistChip(onClick = { }, label = { Text("#$name") })
                }
            }
        }
        Text(
            text = stringResource(R.string.preview_mode_supports_headings_bold_code_blocks_),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
