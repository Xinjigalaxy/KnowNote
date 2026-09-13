package com.xinjigalaxy.knownotes.ui.note

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.outlined.FormatSize
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.xinjigalaxy.knownotes.R
import com.xinjigalaxy.knownotes.data.settings.READ_FONT_MAX
import com.xinjigalaxy.knownotes.data.settings.READ_FONT_MIN
import com.xinjigalaxy.knownotes.data.settings.ReadMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.components.formatFullTime
import com.xinjigalaxy.knownotes.ui.markdown.MarkupColor
import com.xinjigalaxy.knownotes.data.media.NoteImages
import com.xinjigalaxy.knownotes.ui.markdown.NoteBodyText
import com.xinjigalaxy.knownotes.ui.markdown.MarkupSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    var showReadingMenu by remember { mutableStateOf(false) }

    // 图片：用系统相册选择器（PickVisualMedia），不必申请读相册权限
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageAlt = stringResource(R.string.image)
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // 拷贝 + 缩放可能涉及几十兆的读写，别放在主线程
                val name = withContext(Dispatchers.IO) { NoteImages.importFrom(context, uri) }
                if (name == null) {
                    Toast.makeText(context, R.string.image_insert_failed, Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.insertImageReference(name, imageAlt)
                }
            }
        }
    }

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
                    // 阅读显示设置（MD / 原文、字号）：收进二级菜单，
                    // 之前放在底栏会一直压着正文，影响阅读。
                    Box {
                        IconButton(onClick = { showReadingMenu = true }) {
                            Icon(
                                imageVector = Icons.Outlined.FormatSize,
                                contentDescription = stringResource(R.string.read_options),
                            )
                        }
                        DropdownMenu(
                            expanded = showReadingMenu,
                            onDismissRequest = { showReadingMenu = false },
                        ) {
                            ReadingMenuContent(state = state, viewModel = viewModel)
                        }
                    }
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
                value = state.contentField,
                onValueChange = viewModel::setContentField,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
                label = { Text(stringResource(R.string.body)) },
                placeholder = { Text(stringResource(R.string.jot_fragmented_notes_anytime_code_snippets_paste)) },
            )

            FormatToolbar(
                state = state,
                viewModel = viewModel,
                onPickImage = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
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
        val scale = state.readFontScale
        if (state.title.isNotBlank()) {
            val titleStyle = MaterialTheme.typography.headlineSmall
            Text(
                text = state.title,
                style = titleStyle.copy(
                    fontSize = titleStyle.fontSize * scale,
                    lineHeight = titleStyle.lineHeight * scale,
                ),
            )
        }
        if (state.content.isBlank()) {
            Text(
                text = stringResource(R.string.body_is_still_empty_tap_the_top_right_to_switch_),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            when (state.readMode) {
                ReadMode.MD -> NoteBodyText(
                    content = state.content,
                    modifier = Modifier.fillMaxWidth(),
                    fontScale = state.readFontScale,
                )
                // 文本模式：连自定义标记本身一起原样显示，方便手动改
                ReadMode.TEXT -> PlainNoteText(
                    text = state.content,
                    scale = state.readFontScale,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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

/**
 * 编辑器格式工具栏（v1.7.0）。
 *
 * 全部作用在**当前选区**上：选中文字就包住它，没选就插入一对空标记把光标放中间。
 * 按钮的选中态读的是当前选区是否已经带该标记，所以「加粗 → 再点一次」就是取消。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatToolbar(
    state: NoteEditViewModel.State,
    viewModel: NoteEditViewModel,
    onPickImage: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val selected = !state.selection.collapsed

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.format_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(8.dp))
            if (!selected) {
                Text(
                    text = stringResource(R.string.format_select_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = viewModel.selectionHas("**", "**"),
                onClick = { viewModel.applyBold() },
                label = { Text(stringResource(R.string.format_bold), fontWeight = FontWeight.Bold) },
            )
            FilterChip(
                selected = viewModel.selectionHas("*", "*"),
                onClick = { viewModel.applyItalic() },
                label = { Text(stringResource(R.string.format_italic), fontStyle = FontStyle.Italic) },
            )
            MarkupSize.entries.forEach { size ->
                AssistChip(
                    onClick = { viewModel.applySize(size) },
                    label = { Text(stringResource(size.labelRes())) },
                )
            }
            AssistChip(
                onClick = onPickImage,
                label = { Text(stringResource(R.string.insert_image)) },
            )
            MarkupColor.entries.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color.color(dark))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { viewModel.applyColor(color) },
                )
            }
        }
    }
}

private fun MarkupSize.labelRes(): Int = when (this) {
    MarkupSize.SMALL -> R.string.size_small
    MarkupSize.LARGE -> R.string.size_large
    MarkupSize.XLARGE -> R.string.size_xlarge
}

/**
 * 阅读显示二级菜单的内容：展示方式 + 字号滑块。
 *
 * 字号用「倍率」而不是绝对值：全局字号（设置页）改了，这里仍然按同一个比例走，
 * 两处设置不会互相打架。滑块旁边直接显示换算后的 sp 值，方便对着调。
 *
 * 放在顶栏菜单里而不是底栏：底栏会一直压着正文，长笔记读起来碍事。
 */
@Composable
private fun ReadingMenuContent(state: NoteEditViewModel.State, viewModel: NoteEditViewModel) {
    val bodySp = MaterialTheme.typography.bodyMedium.fontSize
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.read_options),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.readMode == mode,
                    onClick = { viewModel.setReadMode(mode) },
                    label = {
                        Text(
                            stringResource(
                                if (mode == ReadMode.MD) R.string.read_mode_md else R.string.read_mode_text,
                            ),
                        )
                    },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(
                R.string.read_font_value,
                (bodySp.value * state.readFontScale).roundToInt(),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Row(
            modifier = Modifier.width(320.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "A", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = state.readFontScale,
                onValueChange = viewModel::setReadFontScale,
                valueRange = READ_FONT_MIN..READ_FONT_MAX,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Text(text = "A", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** 文本模式：原文照排（含标记本身），只做字号缩放。 */
@Composable
private fun PlainNoteText(text: String, scale: Float, modifier: Modifier = Modifier) {
    val base = MaterialTheme.typography.bodyMedium
    Text(
        text = text,
        modifier = modifier,
        style = base.copy(
            fontSize = base.fontSize * scale,
            lineHeight = base.lineHeight * scale,
        ),
    )
}
