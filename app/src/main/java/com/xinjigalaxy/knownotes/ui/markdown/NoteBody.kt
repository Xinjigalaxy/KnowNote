package com.xinjigalaxy.knownotes.ui.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xinjigalaxy.knownotes.R
import com.xinjigalaxy.knownotes.data.media.NoteImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 笔记正文：文字段 + 图片块。
 *
 * 图片是"行内元素、独占整行"：按宽度铺满，高度自适应；文字段仍走 [MarkdownText]，
 * 所以字号倍率（阅读页滑块）与行内标记对文字照常生效。
 */
@Composable
fun NoteBodyText(
    content: String,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    val blocks = remember(content) { splitNoteBlocks(content) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is NoteBlock.Text -> MarkdownText(
                    text = block.markdown,
                    modifier = Modifier.fillMaxWidth(),
                    fontScale = fontScale,
                )

                is NoteBlock.Image -> NoteImage(
                    name = block.name,
                    alt = block.alt,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 单张图片：解码放在 IO 线程，避免大图卡住列表 / 阅读页。 */
@Composable
private fun NoteImage(name: String, alt: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(name) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(name) {
        bitmap = withContext(Dispatchers.IO) { NoteImages.loadBitmap(context, name) }
    }

    val loaded = bitmap
    if (loaded == null) {
        // 图片丢了（清过数据、或从别处同步来的笔记）——说明清楚，别留一片空白让人猜
        Text(
            text = stringResource(R.string.image_missing, name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = modifier,
        )
        return
    }
    Image(
        bitmap = loaded.asImageBitmap(),
        contentDescription = alt.ifBlank { null },
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.FillWidth,
    )
}
