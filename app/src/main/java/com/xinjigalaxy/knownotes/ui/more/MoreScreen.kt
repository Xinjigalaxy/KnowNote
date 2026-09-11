package com.xinjigalaxy.knownotes.ui.more

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenExport: () -> Unit,
    onOpenSync: () -> Unit,
    viewModel: MoreViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    var showAbout by remember { mutableStateOf(false) }
    var showPurge by remember { mutableStateOf(false) }
    var purgeMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("更多") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "数据库概览",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        StatsRow("在线笔记", stats?.notes?.toString() ?: "—")
                        StatsRow("标签 / 分组", "${stats?.tags ?: 0} / ${stats?.groups ?: 0}")
                        StatsRow("软删除待清理", stats?.deleted?.toString() ?: "0")
                        StatsRow("变更日志", stats?.changeLog?.toString() ?: "0")
                        StatsRow(
                            "全文检索",
                            if (viewModel.searchEngineIsFullText) {
                                "${viewModel.searchEngine} 已启用"
                            } else {
                                "${viewModel.searchEngine}（无可用全文索引）"
                            },
                        )
                        StatsRow("设备标识", viewModel.deviceId)
                        StatsRow("SQLite 版本", viewModel.sqliteVersion)
                        StatsRow("判定依据", viewModel.searchEngineDecision)
                        StatsRow(
                            "探测结果",
                            viewModel.searchEngineProbeError ?: "FTS5 与 FTS4 任一可用即无错误",
                        )
                        if (!viewModel.searchEngineIsFullText) {
                            Text(
                                text = "本机 SQLite 未提供全文索引，检索已降级为 LIKE 扫描；探测信息：${viewModel.searchEngineProbeError ?: "—"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item {
                EntryCard(
                    icon = Icons.Outlined.FileDownload,
                    title = "导出数据",
                    subtitle = "支持 .json / .csv / .db 三种格式",
                    onClick = onOpenExport,
                )
            }

            item {
                EntryCard(
                    icon = Icons.Outlined.Sync,
                    title = "局域网同步",
                    subtitle = "第三阶段实现：一主多从 + 增量变更日志",
                    onClick = onOpenSync,
                )
            }

            item {
                EntryCard(
                    icon = Icons.Outlined.DeleteSweep,
                    title = "清理软删除笔记",
                    subtitle = "把已删除的笔记从数据库彻底移除",
                    onClick = { showPurge = true },
                )
            }

            item {
                EntryCard(
                    icon = Icons.Outlined.Info,
                    title = "关于",
                    subtitle = "KnowNote 1.0.0-demo（Material 3）",
                    onClick = { showAbout = true },
                )
            }

            purgeMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    if (showPurge) {
        AlertDialog(
            onDismissRequest = { showPurge = false },
            title = { Text("彻底清理软删除笔记？") },
            text = { Text("当前有 ${stats?.deleted ?: 0} 条软删除笔记，清理后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showPurge = false
                    viewModel.purgeDeleted { removed -> purgeMessage = "已清理 $removed 条笔记" }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { showPurge = false }) { Text("取消") } },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于 KnowNote") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("安卓零碎知识点记事本 · 1.0 demo")
                    HorizontalDivider()
                    Text("· Kotlin + Jetpack Compose + Material 3")
                    Text("· Room(SQLite) + FTS5 全文检索")
                    Text("· MVVM + Repository，user_version 增量迁移")
                    Text("· 导出 .db / .json / .csv")
                    Text("主题种子色 #39C5BB")
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("好") } },
        )
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun EntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
