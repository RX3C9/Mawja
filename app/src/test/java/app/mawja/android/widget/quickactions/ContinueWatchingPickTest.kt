package app.mawja.android.widget.quickactions

import app.mawja.android.data.local.VideoHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingPickTest {
    private fun entry(
        id: String,
        at: Long,
        position: Long = 60_000L,
        duration: Long = 600_000L,
        isMusic: Boolean = false,
        isShort: Boolean = false,
        isLocal: Boolean = false,
    ) = VideoHistoryEntry(
        videoId = id,
        position = position,
        duration = duration,
        timestamp = at,
        title = id,
        thumbnailUrl = "",
        isMusic = isMusic,
        isShort = isShort,
        isLocal = isLocal,
    )

    @Test
    fun picksTheNewestPartWatchedVideo() {
        val picked = ContinueWatchingPick.pick(listOf(entry("old", at = 1), entry("new", at = 3), entry("mid", at = 2)))
        assertEquals("new", picked?.videoId)
    }

    @Test
    fun skipsMusicShortsAndLocalFiles() {
        val picked =
            ContinueWatchingPick.pick(
                listOf(
                    entry("song", at = 9, isMusic = true),
                    entry("short", at = 8, isShort = true),
                    entry("file", at = 7, isLocal = true),
                    entry("video", at = 1),
                ),
            )
        assertEquals("video", picked?.videoId)
    }

    @Test
    fun skipsUnstartedAndFinishedVideos() {
        val picked =
            ContinueWatchingPick.pick(
                listOf(
                    entry("unstarted", at = 9, position = 0L),
                    entry("finished", at = 8, position = 590_000L),
                    entry("noDuration", at = 7, duration = 0L),
                    entry("half", at = 1, position = 300_000L),
                ),
            )
        assertEquals("half", picked?.videoId)
    }

    @Test
    fun nothingToResumeMeansHistory() {
        assertNull(ContinueWatchingPick.pick(emptyList()))
        assertNull(ContinueWatchingPick.pick(listOf(entry("song", at = 1, isMusic = true))))
    }

    @Test
    fun continueWatchingIsInTheDefaultSet() {
        assertEquals(
            listOf(QuickShortcut.DOWNLOADS, QuickShortcut.LIBRARY, QuickShortcut.CONTINUE_WATCHING),
            QuickShortcut.DEFAULT,
        )
        assertEquals(listOf(QuickShortcut.CONTINUE_WATCHING), QuickShortcut.decode("continue"))
        // A saved choice from before keeps its old shortcuts.
        assertEquals(
            listOf(QuickShortcut.DOWNLOADS, QuickShortcut.HISTORY, QuickShortcut.RECOGNIZE),
            QuickShortcut.decode("downloads,history,recognize"),
        )
    }
}
