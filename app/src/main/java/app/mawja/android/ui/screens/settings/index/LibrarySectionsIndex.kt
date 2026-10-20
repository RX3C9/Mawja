package app.mawja.android.ui.screens.settings.index

import app.mawja.android.R
import app.mawja.android.ui.components.library.LibrarySection
import app.mawja.android.ui.components.settings.SettingEntry
import app.mawja.android.ui.components.settings.SettingsDestination

internal object LibrarySectionsIndex {
    private val entries: Map<LibrarySection, SettingEntry> =
        LibrarySection.entries.associateWith { section ->
            SettingEntry(
                key = "library.${section.name.lowercase()}",
                title = section.titleRes,
                section =
                    if (section in
                        LibrarySection.settingsAndData
                    ) {
                        R.string.library_settings_data_header
                    } else {
                        R.string.library_section_header
                    },
                destination = SettingsDestination.LIBRARY,
            )
        }

    fun entry(section: LibrarySection): SettingEntry = entries.getValue(section)

    val all: List<SettingEntry> = LibrarySection.entries.map(::entry)
}
