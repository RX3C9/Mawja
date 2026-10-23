package app.mawja.android.ui.screens.settings.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mawja.android.R
import app.mawja.android.ui.components.library.LibrarySection
import app.mawja.android.ui.components.settings.SettingsPage
import app.mawja.android.ui.components.settings.switch
import app.mawja.android.ui.reorder.ListOrder
import app.mawja.android.ui.screens.settings.index.LibrarySectionsIndex

/** One switch per Library section, laid out as Library lays them out; a hidden section is left out of Library. */
@Composable
internal fun LibrarySettingsScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: LibrarySettingsViewModel = hiltViewModel(),
) {
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    val separatePlaylists by viewModel.separatePlaylists.collectAsStateWithLifecycle()
    val shortsContent by viewModel.shortsContent.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val order by viewModel.order.collectAsStateWithLifecycle()
    val contentOrder = ListOrder.apply(LibrarySection.content(separatePlaylists), order) { it.name }
    val settingsOrder = ListOrder.apply(LibrarySection.settingsAndData, order) { it.name }

    fun enabled(section: LibrarySection): Boolean =
        when (section) {
            LibrarySection.SAVED_SHORTS -> shortsContent
            LibrarySection.NOTES -> notes
            else -> true
        }

    SettingsPage(title = stringResource(R.string.library), onBack = onBack, highlight = highlight) {
        group(key = "library.content", header = R.string.library_section_header) {
            contentOrder.forEach { section ->
                switch(
                    LibrarySectionsIndex.entry(section),
                    checked = section !in hidden,
                    onCheckedChange = { viewModel.setShown(section, it) },
                    enabled = enabled(section),
                )
            }
        }
        group(key = "library.settings_data", header = R.string.library_settings_data_header) {
            settingsOrder.forEach { section ->
                switch(
                    LibrarySectionsIndex.entry(section),
                    checked = section !in hidden,
                    onCheckedChange = { viewModel.setShown(section, it) },
                    enabled = enabled(section),
                )
            }
        }
        group(key = "library.order", header = R.string.library_order_header, footer = R.string.library_order_footer) {
            row("library.order.content") { shape ->
                LibrarySectionOrderList(
                    sections = contentOrder,
                    onOrderChanged = { viewModel.setOrder(it, settingsOrder, separatePlaylists) },
                    modifier = Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
            row("library.order.settings_data") { shape ->
                LibrarySectionOrderList(
                    sections = settingsOrder,
                    onOrderChanged = { viewModel.setOrder(contentOrder, it, separatePlaylists) },
                    modifier = Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
            if (order.isNotEmpty()) {
                row("library.order.reset") { shape ->
                    Box(
                        modifier = Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        TextButton(onClick = viewModel::resetOrder, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.library_order_reset))
                        }
                    }
                }
            }
        }
    }
}
