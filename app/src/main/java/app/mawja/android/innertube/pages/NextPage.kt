package app.mawja.android.innertube.pages

import app.mawja.android.innertube.models.Album
import app.mawja.android.innertube.models.Artist
import app.mawja.android.innertube.models.BrowseEndpoint
import app.mawja.android.innertube.models.PlaylistPanelVideoRenderer
import app.mawja.android.innertube.models.SongItem
import app.mawja.android.innertube.models.WatchEndpoint
import app.mawja.android.innertube.models.oddElements
import app.mawja.android.innertube.models.splitBySeparator
import app.mawja.android.innertube.utils.parseTime

data class NextResult(
    val title: String? = null,
    val items: List<SongItem>,
    val currentIndex: Int? = null,
    val lyricsEndpoint: BrowseEndpoint? = null,
    val relatedEndpoint: BrowseEndpoint? = null,
    val continuation: String?,
    val endpoint: WatchEndpoint, // current or continuation next endpoint
)

object NextPage {
    fun fromPlaylistPanelVideoRenderer(renderer: PlaylistPanelVideoRenderer): SongItem? {
        val longByLineRuns = renderer.longBylineText?.runs?.splitBySeparator() ?: return null
        val counts =
            longByLineRuns
                .drop(1)
                .filter { segment -> segment.firstOrNull()?.navigationEndpoint == null }
                .mapNotNull { segment -> segment.firstOrNull()?.text }
                .filter { text -> text.any { !it.isDigit() } }
        return SongItem(
            id = renderer.videoId ?: return null,
            title =
                renderer.title
                    ?.runs
                    ?.firstOrNull()
                    ?.text ?: return null,
            artists =
                longByLineRuns.firstOrNull()?.oddElements()?.map {
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                    )
                } ?: return null,
            album =
                longByLineRuns
                    .getOrNull(1)
                    ?.firstOrNull()
                    ?.takeIf {
                        it.navigationEndpoint?.browseEndpoint != null
                    }?.let {
                        Album(
                            name = it.text,
                            id = it.navigationEndpoint?.browseEndpoint?.browseId!!,
                        )
                    },
            duration =
                renderer.lengthText
                    ?.runs
                    ?.firstOrNull()
                    ?.text
                    ?.parseTime() ?: return null,
            musicVideoType = renderer.navigationEndpoint.musicVideoType,
            thumbnail =
                renderer.thumbnail.thumbnails
                    .lastOrNull()
                    ?.url ?: return null,
            explicit =
                renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null,
            viewCountText = counts.getOrNull(0),
            likeCountText = counts.getOrNull(1),
        )
    }
}
