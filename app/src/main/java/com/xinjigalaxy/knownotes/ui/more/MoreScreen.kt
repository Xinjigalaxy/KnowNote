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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xinjigalaxy.knownotes.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider

/**
 * 「关于」里的版本号从包信息里读，别再写死 —— 之前一直显示 1.1.0-demo，跟实际装的版本对不上。
 */
private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenExport: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MoreViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    var showAbout by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.more)) }) },
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
                            text = stringResource(R.string.database_overview),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        StatsRow(stringResource(R.string.active_notes), stats?.notes?.toString() ?: "—")
                        StatsRow(stringResource(R.string.tags_groups), "${stats?.tags ?: 0} / ${stats?.groups ?: 0}")
                        StatsRow(stringResource(R.string.trash_soft_delete), stats?.deleted?.toString() ?: "0")
                        StatsRow(stringResource(R.string.changelog), stats?.changeLog?.toString() ?: "0")
                        StatsRow(
                            stringResource(R.string.full_text_search),
                            if (viewModel.searchEngineIsFullText) {
                                stringResource(R.string.viewmodel_searchengine_enabled, viewModel.searchEngine)
                            } else {
                                stringResource(R.string.viewmodel_searchengine_no_full_text_index_availa, viewModel.searchEngine)
                            },
                        )
                        StatsRow(stringResource(R.string.device_identifier), viewModel.deviceId)
                        StatsRow(stringResource(R.string.sqlite_version), viewModel.sqliteVersion)
                        StatsRow(stringResource(R.string.decision_basis), viewModel.searchEngineDecision)
                        StatsRow(
                            stringResource(R.string.probe_result),
                            viewModel.searchEngineProbeError ?: stringResource(R.string.no_error_while_fts5_or_fts4_is_available),
                        )
                        if (!viewModel.searchEngineIsFullText) {
                            Text(
                                text = stringResource(R.string.this_device_s_sqlite_provides_no_full_text_index, viewModel.searchEngineProbeError ?: "—"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            item {
                EntryCard(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.trash),
                    subtitle = if ((stats?.deleted ?: 0) > 0) {
                        stringResource(R.string.stats_deleted_deleted_notes_you_can_restore_or_p, stats?.deleted ?: 0)
                    } else {
                        stringResource(R.string.notes_deleted_by_long_press_land_here_first)
                    },
                    onClick = onOpenTrash,
                )
            }

            item {
                EntryCard(
                    icon = Icons.Outlined.FileDownload,
                    title = stringResource(R.string.export_data),
                    subtitle = stringResource(R.string.supports_json_csv_db),
                    onClick = onOpenExport,
                )
            }

            item {
                EntryCard(
                    icon = Icons.Outlined.Sync,
                    title = stringResource(R.string.lan_sync),
                    subtitle = stringResource(R.string.phase_3_one_host_with_many_clients_incremental_c),
                    onClick = onOpenSync,
                )
            }

            item {
                EntryCard(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(R.string.settings),
                    subtitle = stringResource(R.string.theme_mode_dynamic_color_scheduled_trash_cleanup),
                    onClick = onOpenSettings,
                )
            }

            item {
                EntryCard(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.about),
                    subtitle = "KnowNote ${appVersion(context)}（Material 3）",
                    onClick = { showAbout = true },
                )
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about_knownote)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.knownote_for_android_appversion_context, appVersion(context)))
                    HorizontalDivider()
                    Text("· Kotlin + Jetpack Compose + Material 3")
                    Text(stringResource(R.string.room_sqlite_full_text_search_fts5_first_falls_ba))
                    Text(stringResource(R.string.mvvm_repository_user_version_incremental_migrati))
                    Text(stringResource(R.string.chinese_split_per_character_for_substring_search))
                    Text(stringResource(R.string.markdown_rendering_search_history_filter_memory_))
                    Text(stringResource(R.string.export_db_json_csv))
                    Text(stringResource(R.string.theme_seed_color_39c5bb))
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.ok)) } },
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
