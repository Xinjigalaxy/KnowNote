package com.xinjigalaxy.knownotes.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xinjigalaxy.knownotes.KnowNoteApp
import com.xinjigalaxy.knownotes.ui.manage.GroupViewModel
import com.xinjigalaxy.knownotes.ui.manage.TagViewModel
import com.xinjigalaxy.knownotes.ui.more.ExportViewModel
import com.xinjigalaxy.knownotes.ui.more.MoreViewModel
import com.xinjigalaxy.knownotes.ui.note.NoteEditViewModel
import com.xinjigalaxy.knownotes.ui.note.NoteListViewModel
import com.xinjigalaxy.knownotes.ui.trash.TrashViewModel

object AppViewModelProvider {

    val Factory = viewModelFactory {
        initializer { NoteListViewModel(app().container.repository, app().container.uiPrefs) }
        initializer { NoteEditViewModel(app().container.repository) }
        initializer { GroupViewModel(app().container.repository) }
        initializer { TagViewModel(app().container.repository) }
        initializer { TrashViewModel(app().container.repository) }
        initializer { ExportViewModel(app().container.repository, app().container.exporter) }
        initializer { MoreViewModel(app().container.repository) }
    }
}

private fun CreationExtras.app(): KnowNoteApp =
    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KnowNoteApp
