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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.data.model.SyncLogEntry
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.components.formatTime

/**
 * 局域网同步页（需求文档 4 / 7 第三阶段）。
 *
 * 一主多从 + 手动触发：一台开「主机模式」，另一台填地址与共享密钥点同步。
 * 主机服务挂在应用作用域上，所以切页面不会掉线；但应用被系统杀掉就会停 ——
 * 这一版没上前台 Service，页面里也直接写明了，不假装能后台常驻。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    viewModel: SyncViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("局域网同步") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(title = "本机") {
                    KeyValue("设备名", state.deviceName)
                    KeyValue("设备 ID", state.deviceId)
                    KeyValue(
                        "局域网地址",
                        state.addresses.joinToString(" / ").ifBlank { "未连上局域网（先连 WiFi）" },
                    )
                    TextButton(onClick = viewModel::refreshAddresses) { Text("刷新地址") }
                }
            }

            item {
                SectionCard(title = "共享密钥") {
                    Text(
                        text = "两台设备必须填同一串，否则主机会直接拒绝（401）。这个串是唯一的门槛，别用 trivial 的。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.key,
                        onValueChange = viewModel::setKey,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密钥") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(state.key))
                        }) { Text("复制") }
                        TextButton(onClick = viewModel::regenerateKey) { Text("重新生成") }
                    }
                }
            }

            item {
                SectionCard(title = "主机模式（本机当主机）") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.hostRunning,
                            onCheckedChange = { on ->
                                if (on) viewModel.startHost() else viewModel.stopHost()
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (state.hostRunning) "监听中 · ${state.hostPort}" else "未启动",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (state.hostRunning) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = if (state.hostRunning) {
                                    "已接待 ${state.servedRequests} 次同步请求"
                                } else {
                                    "打开后其他设备才能连过来"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.portText,
                        onValueChange = viewModel::setPort,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("端口") },
                        singleLine = true,
                        enabled = !state.hostRunning,
                    )
                    state.hostError?.let {
                        Text(
                            text = "服务出错：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = "主机只在应用运行期间有效：这一版没做前台 Service，切后台久了会被系统回收。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            item {
                SectionCard(title = "从机模式（去拉主机）") {
                    OutlinedTextField(
                        value = state.peer,
                        onValueChange = viewModel::setPeer,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("主机地址") },
                        placeholder = { Text("192.168.1.20 或 192.168.1.20:8765") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::pingHost,
                            enabled = !state.busy,
                        ) { Text("探测连通") }
                        Button(
                            onClick = viewModel::syncNow,
                            enabled = !state.busy,
                        ) {
                            Icon(Icons.Outlined.Sync, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.busy) "同步中…" else "开始同步")
                        }
                    }
                    Text(
                        text = if (state.lastSyncAt > 0L) {
                            "上次同步水位线：${formatTime(state.lastSyncAt)}"
                        } else {
                            "还没同步过"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            state.message?.let { message ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "同步日志",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "最近 ${state.log.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.log.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearLog) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.width(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("清空")
                        }
                    }
                }
            }

            if (state.log.isEmpty()) {
                item {
                    Text(
                        text = "还没有同步记录。第一次同步之后，这里会显示拉了/推了多少条、有没有冲突。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                items(state.log, key = { it.id }) { entry ->
                    SyncLogCard(entry)
                }
            }
        }
    }
}

@Composable
private fun SyncLogCard(entry: SyncLogEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (entry.role == SyncLogEntry.ROLE_HOST) "接客（主机）" else "上门（从机）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (entry.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.peer,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatTime(entry.at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (entry.ok) {
                Text(
                    text = "拉取 ${entry.pulled} 条 · 推送 ${entry.pushed} 条" +
                        if (entry.conflicts > 0) " · 打平 ${entry.conflicts} 条" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.message.isNotBlank()) {
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.ok) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(
            text = key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
