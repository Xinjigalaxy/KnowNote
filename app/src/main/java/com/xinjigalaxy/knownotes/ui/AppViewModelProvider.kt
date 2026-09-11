package com.xinjigalaxy.knownotes.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xinjigalaxy.knownotes.KnowNoteApp
import com.xinjigalaxy.knownotes.data.settings.TrashCleanupScheduler
import com.xinjigalaxy.knownotes.ui.manage.GroupNotesViewModel
import com.xinjigalaxy.knownotes.ui.manage.GroupViewModel
import com.xinjigalaxy.knownotes.ui.manage.TagNotesViewModel
import com.xinjigalaxy.knownotes.ui.manage.TagViewModel
import com.xinjigalaxy.knownotes.ui.more.ExportViewModel
import com.xinjigalaxy.knownotes.ui.more.MoreViewModel
import com.xinjigalaxy.knownotes.ui.more.SyncViewModel
import com.xinjigalaxy.knownotes.ui.note.NoteEditViewModel
import com.xinjigalaxy.knownotes.ui.note.NoteListViewModel
import com.xinjigalaxy.knownotes.ui.settings.SettingsViewModel
import com.xinjigalaxy.knownotes.ui.trash.TrashViewModel

object AppViewModelProvider {

    val Factory = viewModelFactory {
        initializer { NoteListViewModel(app().container.repository, app().container.uiPrefs) }
        initializer { NoteEditViewModel(app().container.repository) }
        initializer { GroupViewModel(app().container.repository, app().container.uiPrefs) }
        initializer { TagViewModel(app().container.repository, app().container.uiPrefs) }
        initializer { GroupNotesViewModel(app().container.repository, app().container.uiPrefs) }
        initializer { TagNotesViewModel(app().container.repository, app().container.uiPrefs) }
        initializer { TrashViewModel(app().container.repository) }
        initializer { ExportViewModel(app().container.repository, app().container.exporter) }
        initializer { MoreViewModel(app().container.repository) }
        initializer {
            SettingsViewModel(
                repo = app().container.repository,
                settings = app().container.settings,
                prefs = app().container.uiPrefs,
                scheduleCleanup = { enabled -> TrashCleanupScheduler.apply(app(), enabled) },
            )
        }
        initializer {
            SyncViewModel(
                repo = app().container.repository,
                server = app().container.syncServer,
                client = app().container.syncClient,
                coordinator = app().container.syncCoordinator,
                prefs = app().container.uiPrefs,
                appScope = app().container.appScope(),
                deviceName = app().container.deviceName,
            )
        }
    }
}

private fun CreationExtras.app(): KnowNoteApp =
    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KnowNoteApp
