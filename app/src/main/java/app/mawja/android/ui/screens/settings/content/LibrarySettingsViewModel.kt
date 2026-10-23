package app.mawja.android.ui.screens.settings.content

import dagger.hilt.android.lifecycle.HiltViewModel
import app.mawja.android.data.local.PlayerPreferences
import app.mawja.android.ui.components.library.LibrarySection
import app.mawja.android.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class LibrarySettingsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        internal val hidden = preferences.hiddenLibrarySections.map(LibrarySection::fromStored).asState(emptySet())
        val separatePlaylists = preferences.separatePlaylistKinds.asState(false)
        val shortsContent = preferences.shortsContentEnabled.asState(true)
        val notes = preferences.notesEnabled.asState(true)
        val order = preferences.librarySectionOrder.asState(emptyList())

        /** Stores [content] then [settingsAndData] as one order; the default order is stored as nothing. */
        internal fun setOrder(
            content: List<LibrarySection>,
            settingsAndData: List<LibrarySection>,
            separatePlaylists: Boolean,
        ) = write {
            val isDefault =
                content == LibrarySection.content(separatePlaylists) && settingsAndData == LibrarySection.settingsAndData
            preferences.setLibrarySectionOrder(if (isDefault) emptyList() else (content + settingsAndData).map { it.name })
        }

        internal fun resetOrder() = write { preferences.setLibrarySectionOrder(emptyList()) }

        internal fun setShown(
            section: LibrarySection,
            shown: Boolean,
        ) = write { preferences.setLibrarySectionHidden(section.name, hidden = !shown) }
    }
