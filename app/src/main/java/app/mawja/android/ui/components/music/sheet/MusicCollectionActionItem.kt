package app.mawja.android.ui.components.music.sheet

import app.mawja.android.data.music.model.MusicPlaylist
import app.mawja.android.innertube.models.AlbumItem
import app.mawja.android.innertube.models.PlaylistItem
import app.mawja.android.innertube.models.YTItem

data class MusicCollectionActionItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val isAlbum: Boolean = false,
) {
    val shareUrl: String get() = musicCollectionShareUrl(id, isAlbum)
}

/** The YouTube Music link for an album or playlist id. */
fun musicCollectionShareUrl(
    id: String,
    isAlbum: Boolean,
): String = if (isAlbum) "https://music.youtube.com/browse/$id" else "https://music.youtube.com/playlist?list=$id"

fun MusicPlaylist.toCollectionActionItem(isAlbum: Boolean): MusicCollectionActionItem =
    MusicCollectionActionItem(
        id = id,
        title = title,
        subtitle = author,
        thumbnailUrl = thumbnailUrl,
        isAlbum = isAlbum,
    )

fun YTItem.toCollectionActionItem(): MusicCollectionActionItem? =
    when (this) {
        is AlbumItem -> {
            MusicCollectionActionItem(
                id = id,
                title = title,
                subtitle = artists?.joinToString { it.name }.orEmpty(),
                thumbnailUrl = thumbnail,
                isAlbum = true,
            )
        }

        is PlaylistItem -> {
            MusicCollectionActionItem(
                id = id,
                title = title,
                subtitle = author?.name.orEmpty(),
                thumbnailUrl = thumbnail,
                isAlbum = false,
            )
        }

        else -> {
            null
        }
    }
