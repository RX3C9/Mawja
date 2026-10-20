package app.mawja.android.ui.components.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.mawja.android.R
import app.mawja.android.data.local.ContentType
import app.mawja.android.data.local.Duration
import app.mawja.android.data.local.SearchFilter
import app.mawja.android.data.local.SortType
import app.mawja.android.data.local.UploadDate
import app.mawja.android.data.local.WatchState

/** What [this] narrows, in dialog order. Channels and playlists ignore duration and date, so those are left out there. */
internal fun SearchFilter.summaryLabelRes(): List<Int> =
    buildList {
        if (contentType != ContentType.ALL) add(contentType.labelRes())
        if (contentType != ContentType.CHANNELS && contentType != ContentType.PLAYLISTS) {
            if (duration != Duration.ANY) add(duration.labelRes())
            if (uploadDate != UploadDate.ANY) add(uploadDate.labelRes())
            if (watchState != WatchState.ANY) add(watchState.labelRes())
        }
        if (sortType != SortType.RELEVANCE) add(sortType.labelRes())
        features.sorted().forEach { add(it.labelRes()) }
    }

/** One line naming a history entry's filters, or null when it ran unfiltered. */
@Composable
internal fun searchFilterSummary(filter: SearchFilter): String? {
    val labels = filter.summaryLabelRes().map { stringResource(it) }
    if (labels.isEmpty()) return null
    return labels.joinToString(stringResource(R.string.list_separator_dot))
}
