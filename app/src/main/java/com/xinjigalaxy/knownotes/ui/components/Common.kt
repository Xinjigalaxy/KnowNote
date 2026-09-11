package com.xinjigalaxy.knownotes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xinjigalaxy.knownotes.R
import androidx.compose.material3.Text as M3Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 关键词高亮（需求文档 3.3 「结果高亮」）。
 * 只按当前搜索词做字面高亮，不解析 Markdown，保证列表滚动性能。
 */
@Composable
fun HighlightedText(
    text: String,
    terms: List<String>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val annotated = remember(text, terms, color) { highlight(text, terms, color) }
    M3Text(
        text = annotated,
        style = style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun highlight(text: String, terms: List<String>, color: Color): AnnotatedString {
    if (terms.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        terms.forEach { term ->
            if (term.isEmpty()) return@forEach
            var from = text.indexOf(term, startIndex = 0, ignoreCase = true)
            while (from >= 0) {
                addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), from, from + term.length)
                from = text.indexOf(term, startIndex = from + term.length, ignoreCase = true)
            }
        }
    }
}

/** 相对时间：一周内用「多久前」，更早显示日期。 */
@Composable
fun formatTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> stringResource(R.string.just_now)
        diff < 3_600_000L -> stringResource(R.string.diff_60_000l_minutes_ago, diff / 60_000L)
        diff < 86_400_000L -> stringResource(R.string.diff_3_600_000l_hours_ago, diff / 3_600_000L)
        diff < 7 * 86_400_000L -> stringResource(R.string.diff_86_400_000l_days_ago, diff / 86_400_000L)
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
    }
}

/** 全文时间（编辑页副标题）。 */
fun formatFullTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
fun EmptyHint(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
