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
import androidx.compose.ui.res.stringResource
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
import com.xinjigalaxy.knownotes.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.data.model.SyncLogEntry
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.components.formatTime
import com.xinjigalaxy.knownotes.ui.components.rendered

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.lan_sync)) },
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
                SectionCard(title = stringResource(R.string.this_device)) {
                    KeyValue(stringResource(R.string.device_name), state.deviceName)
                    KeyValue(stringResource(R.string.device_id), state.deviceId)
                    KeyValue(
                        stringResource(R.string.lan_address),
                        state.addresses.joinToString(" / ").ifBlank { stringResource(R.string.not_on_a_lan_connect_to_wi_fi_first) },
                    )
                    TextButton(onClick = viewModel::refreshAddresses) { Text(stringResource(R.string.refresh_address)) }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.shared_key)) {
                    Text(
                        text = stringResource(R.string.both_devices_must_use_the_same_key_or_the_host_r),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.key,
                        onValueChange = viewModel::setKey,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.key)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(state.key))
                        }) { Text(stringResource(R.string.copy)) }
                        TextButton(onClick = viewModel::regenerateKey) { Text(stringResource(R.string.regenerate)) }
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.host_mode_this_device_hosts)) {
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
                                text = if (state.hostRunning) stringResource(R.string.listening_state_hostport, state.hostPort) else stringResource(R.string.not_started),
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
                                    stringResource(R.string.served_state_servedrequests_sync_requests, state.servedRequests)
                                } else {
                                    stringResource(R.string.other_devices_can_connect_only_after_you_turn_it)
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
                        label = { Text(stringResource(R.string.port)) },
                        singleLine = true,
                        enabled = !state.hostRunning,
                    )
                    state.hostError?.let {
                        Text(
                            text = stringResource(R.string.service_error_it, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = stringResource(R.string.the_host_is_valid_only_while_the_app_runs_this_v),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.client_mode_pull_from_host)) {
                    OutlinedTextField(
                        value = state.peer,
                        onValueChange = viewModel::setPeer,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.host_address)) },
                        placeholder = { Text(stringResource(R.string.s_192_168_1_20_or_192_168_1_20_8765)) },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::pingHost,
                            enabled = !state.busy,
                        ) { Text(stringResource(R.string.test_connection)) }
                        Button(
                            onClick = viewModel::syncNow,
                            enabled = !state.busy,
                        ) {
                            Icon(Icons.Outlined.Sync, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.busy) stringResource(R.string.syncing) else stringResource(R.string.start_sync))
                        }
                    }
                    Text(
                        text = if (state.lastSyncAt > 0L) {
                            stringResource(R.string.last_sync_watermark_formattime_state_lastsyncat, formatTime(state.lastSyncAt))
                        } else {
                            stringResource(R.string.never_synced)
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
                            text = message.rendered(),
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
                        text = stringResource(R.string.sync_log),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.latest_state_log_size, state.log.size),
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
                            Text(stringResource(R.string.clear))
                        }
                    }
                }
            }

            if (state.log.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_sync_records_yet_after_the_first_sync_this_sh),
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
                    text = if (entry.role == SyncLogEntry.ROLE_HOST) stringResource(R.string.serving_host) else stringResource(R.string.outbound_client),
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
                    text = stringResource(R.string.pulled_entry_pulled_pushed_entry_pushed, entry.pulled, entry.pushed) +
                        if (entry.conflicts > 0) stringResource(R.string.entry_conflicts_conflicts_resolved, entry.conflicts) else "",
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
