package app.mawja.android.widget.quickactions

import app.mawja.android.data.local.VideoHistoryEntry
import app.mawja.android.widget.recent.watchedFraction

/**
 * The video the "Continue watching" shortcut resumes: the newest history entry that is a regular
 * video (not music, a Short or a local file) and is part-way through. Null sends the tap to History.
 */
object ContinueWatchingPick {
    fun pick(entries: List<VideoHistoryEntry>): VideoHistoryEntry? =
        entries
            .asSequence()
            .filter { !it.isMusic && !it.isShort && !it.isLocal }
            .filter { it.videoId.isNotBlank() }
            .filter { watchedFraction(it.position, it.duration) != null }
            .maxByOrNull { it.timestamp }
}
