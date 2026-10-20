package app.mawja.android.ui.screens.recap

import app.mawja.android.data.localmedia.LocalMediaIds
import app.mawja.android.data.stats.RankedItem
import app.mawja.android.data.stats.RankedKind
import app.mawja.android.utils.YouTubeLink

/** Where tapping a ranked row goes; null for rows with no page, such as topics or name-only artists. */
internal fun RankedItem.link(): YouTubeLink? {
    if (id.isBlank() || LocalMediaIds.isLocal(id)) return null
    return when (kind) {
        RankedKind.CHANNEL -> YouTubeLink.Channel(id, isMusic = false)
        RankedKind.VIDEO -> YouTubeLink.Video(id, isMusic = false)
        RankedKind.ARTIST -> YouTubeLink.Channel(id, isMusic = true).takeIf { id.startsWith(BROWSE_ID_PREFIX) }
        RankedKind.TRACK -> YouTubeLink.Video(id, isMusic = true)
        RankedKind.ALBUM -> YouTubeLink.Album(id)
        RankedKind.OTHER -> null
    }
}

private const val BROWSE_ID_PREFIX = "UC"
