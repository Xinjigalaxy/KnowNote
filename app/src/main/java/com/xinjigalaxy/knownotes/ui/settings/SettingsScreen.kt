package com.xinjigalaxy.knownotes.ui.settings

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xinjigalaxy.knownotes.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinjigalaxy.knownotes.data.settings.AppLanguage
import com.xinjigalaxy.knownotes.data.settings.AppLocales
import com.xinjigalaxy.knownotes.data.settings.FontScale
import com.xinjigalaxy.knownotes.data.settings.TextColorOption
import com.xinjigalaxy.knownotes.data.settings.ThemeMode
import com.xinjigalaxy.knownotes.ui.AppViewModelProvider
import com.xinjigalaxy.knownotes.ui.components.formatTime
import com.xinjigalaxy.knownotes.ui.components.rendered

/**
 * 设置页（v1.4.0）：外观（主题模式 + 动态取色）与回收站定时清理。
 *
 * 主题切换是即时的：设置写进 AppSettings 的 StateFlow，MainActivity 直接 collect，
 * 改一下这里整棵树就换肤，不用重建 Activity。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.settings)) },
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
                SectionCard(title = stringResource(R.string.lang_title)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLanguage.entries.forEach { language ->
                            FilterChip(
                                selected = state.language == language,
                                onClick = {
                                    viewModel.setLanguage(language)
                                    // 33+ 交给系统 LocaleManager 重建；低版本得自己来
                                    if (Build.VERSION.SDK_INT < AppLocales.PER_APP_LANGUAGE_API) {
                                        (context as? Activity)?.recreate()
                                    }
                                },
                                label = { Text(stringResource(language.labelRes)) },
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.lang_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            item {
                SectionCard(title = stringResource(R.string.appearance)) {
                    Text(
                        text = stringResource(R.string.theme_mode),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(stringResource(mode.labelRes)) },
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dynamic_color_android_12),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = if (dynamicColorSupported) {
                                    stringResource(R.string.generate_theme_colors_from_the_wallpaper_turn_of)
                                } else {
                                    stringResource(R.string.this_device_runs_android_build_version_release_w, Build.VERSION.RELEASE)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Switch(
                            checked = state.dynamicColor && dynamicColorSupported,
                            onCheckedChange = viewModel::setDynamicColor,
                            enabled = dynamicColorSupported,
                        )
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.reading_section)) {
                    Text(
                        text = stringResource(R.string.font_scale),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FontScale.entries.forEach { scale ->
                            FilterChip(
                                selected = state.fontScale == scale,
                                onClick = { viewModel.setFontScale(scale) },
                                label = { Text(stringResource(scale.labelRes)) },
                            )
                        }
                    }
                    // 就地预览：不用跳出去看笔记，改一下当场就能判断合不合适
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.font_scale_preview),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }

                    Text(
                        text = stringResource(R.string.text_color),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val dark = isSystemInDarkTheme()
                        TextColorOption.entries.forEach { option ->
                            val selected = state.textColor == option
                            val swatch = option.colorFor(dark)?.let { Color(it) }
                                ?: MaterialTheme.colorScheme.onSurface
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(swatch)
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            shape = CircleShape,
                                        )
                                        .clickable { viewModel.setTextColor(option) },
                                )
                                Text(
                                    text = stringResource(option.labelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.trash)) {
                    InfoRow(stringResource(R.string.current_trash), stringResource(R.string.state_trashcount_items, state.trashCount))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.scheduled_cleanup), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = stringResource(R.string.notes_older_than_the_retention_period_are_perman),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Switch(
                            checked = state.autoPurgeTrash,
                            onCheckedChange = viewModel::setAutoPurgeTrash,
                        )
                    }

                    Text(
                        text = stringResource(R.string.retention_days),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.retentionChoices.forEach { days ->
                            FilterChip(
                                selected = state.retentionDays == days,
                                onClick = { viewModel.setRetentionDays(days) },
                                enabled = state.autoPurgeTrash,
                                label = { Text(stringResource(R.string.days_days, days)) },
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = viewModel::purgeNow,
                            enabled = !state.busy && state.trashCount > 0,
                        ) {
                            Text(if (state.busy) stringResource(R.string.cleaning) else stringResource(R.string.clean_up_now))
                        }
                        Spacer(Modifier.width(12.dp))
                        if (state.lastPurgeAt > 0L) {
                            Text(
                                text = stringResource(R.string.last_cleanup_formattime_state_lastpurgeat_remove, formatTime(state.lastPurgeAt), state.lastPurgeCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.scheduled_cleanup_runs_on_workmanager_once_a_day) +
                            stringResource(R.string.cleanup_results_also_sync_to_other_devices_via_t),
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
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = message.rendered(),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            TextButton(onClick = viewModel::consumeMessage) { Text(stringResource(R.string.got_it)) }
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.settings_live_in_sharedpreferences_knownotes_ui_),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(96.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
