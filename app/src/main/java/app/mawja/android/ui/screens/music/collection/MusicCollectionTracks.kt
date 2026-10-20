package app.mawja.android.ui.screens.music.collection

import app.mawja.android.data.model.Video
import app.mawja.android.data.music.model.MusicTrack
import app.mawja.android.data.music.model.toMusicTrack

/** A stored song as a row of a music page; lengths are stored in seconds already. */
internal fun Video.toCollectionTrack(): MusicTrack = toMusicTrack()

/** A song as the database stores it; what a music page does not know is left blank, never guessed. */
internal fun MusicTrack.toStoredVideo(): Video =
    Video(
        id = videoId,
        title = title,
        channelName = artist,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        viewCount = views,
        uploadDate = "",
        timestamp = 0L,
        isMusic = true,
    )
