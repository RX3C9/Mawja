package app.mawja.android.ui.screens.playlists

import app.mawja.android.data.local.LikedVideoInfo
import app.mawja.android.data.model.Video
import app.mawja.android.data.model.toVideo

/** A like as a row of the Liked videos page; when it was liked stands in for when it was added. */
internal fun LikedVideoInfo.toPlaylistVideo(): Video = toVideo().copy(timestamp = 0L, viewCount = 0L, addedAtInPlaylist = likedAt)

/** This like with what YouTube reports for the video, keeping any title or picture it already had. */
internal fun LikedVideoInfo.withDetailsOf(video: Video): LikedVideoInfo =
    copy(
        title = title.ifBlank { video.title },
        thumbnail = thumbnail.ifBlank { video.thumbnailUrl },
        channelName = channelName.ifBlank { video.channelName },
        channelId = video.channelId.takeIf(String::isNotBlank) ?: channelId,
        durationSeconds = video.duration.takeIf { it > 0 } ?: durationSeconds,
    )
