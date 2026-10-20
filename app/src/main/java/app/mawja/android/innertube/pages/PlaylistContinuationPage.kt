package app.mawja.android.innertube.pages

import app.mawja.android.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
