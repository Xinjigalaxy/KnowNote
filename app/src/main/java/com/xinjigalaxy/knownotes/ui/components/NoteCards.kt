package com.xinjigalaxy.knownotes.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.unit.dp
import com.xinjigalaxy.knownotes.R
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.ui.NoteLayout

/**
 * 笔记卡片（笔记页 / 分组页 / 标签页共用）。
 *
 * 抽出来是因为「分组」「标签」展开后要显示组内笔记，样式必须和笔记页完全一致——
 * 三处各写一份迟早会走样。交互约定同样是：点击 = 查看，长按 = 编辑。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    item: NoteWithTags,
    terms: List<String>,
    groupName: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
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
                text = item.note.title.ifBlank { stringResource(R.string.untitled_note) },
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

/** 列表 ↔ 瀑布流 切换按钮（笔记页 / 分组页 / 标签页共用同一份偏好）。 */
@Composable
fun LayoutToggleButton(
    layout: NoteLayout,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (layout == NoteLayout.STAGGERED) {
                Icons.Outlined.ViewAgenda
            } else {
                Icons.Outlined.GridView
            },
            contentDescription = if (layout == NoteLayout.STAGGERED) stringResource(R.string.switch_to_list_view) else stringResource(R.string.switch_to_grid_view),
        )
    }
}

/** 瀑布流卡片：紧凑、高度随内容自然变化，才形成错落效果。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StaggeredNoteCard(
    item: NoteWithTags,
    terms: List<String>,
    groupName: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HighlightedText(
                text = item.note.title.ifBlank { stringResource(R.string.untitled_note) },
                terms = terms,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 3,
            )
            if (item.note.content.isNotBlank()) {
                HighlightedText(
                    text = item.note.content,
                    terms = terms,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 8,
                )
            }
            groupName?.let {
                Text(
                    text = "▸ $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (item.tags.isNotEmpty()) {
                Text(
                    text = item.tags.joinToString(" ") { "#${it.name}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatTime(item.note.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
