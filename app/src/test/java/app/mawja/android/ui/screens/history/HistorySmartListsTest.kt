package app.mawja.android.ui.screens.history

import app.mawja.android.data.local.VideoHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySmartListsTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun entry(
        id: String,
        daysAgo: Long = 1,
        position: Long = 0,
        duration: Long = 100_000,
        channel: String = "c-$id",
        isShort: Boolean = false,
    ) = VideoHistoryEntry(
        videoId = id,
        position = position,
        duration = duration,
        timestamp = now - daysAgo * day,
        title = id,
        thumbnailUrl = "",
        channelName = "Name $channel",
        channelId = channel,
        isShort = isShort,
    )

    @Test
    fun `no list keeps everything in order`() {
        val entries = listOf(entry("a"), entry("b"))
        assertEquals(entries, HistorySmartLists.apply(null, entries, now))
    }

    @Test
    fun `unfinished keeps started rows short of the end and skips shorts`() {
        val entries =
            listOf(
                entry("barely", position = 1_000),
                entry("half", position = 50_000),
                entry("done", position = 95_000),
                entry("short", position = 50_000, isShort = true),
                entry("unknown", position = 50_000, duration = 0),
            )
        assertEquals(listOf("half"), HistorySmartLists.apply(HistorySmartList.Unfinished, entries, now).map { it.videoId })
    }

    @Test
    fun `long ago keeps rows older than the cutoff`() {
        val entries = listOf(entry("new", daysAgo = 5), entry("edge", daysAgo = 59), entry("old", daysAgo = 61), entry("older", daysAgo = 400))
        assertEquals(listOf("old", "older"), HistorySmartLists.apply(HistorySmartList.LongAgo, entries, now).map { it.videoId })
    }

    @Test
    fun `top channels keeps the channels watched most and drops one-offs`() {
        val entries =
            listOf(
                entry("a1", channel = "a"),
                entry("b1", channel = "b"),
                entry("a2", channel = "a"),
                entry("b2", channel = "b"),
                entry("a3", channel = "a"),
                entry("solo", channel = "s"),
            )
        assertEquals(
            listOf("a1", "b1", "a2", "b2", "a3"),
            HistorySmartLists.apply(HistorySmartList.TopChannels, entries, now).map { it.videoId },
        )
    }

    @Test
    fun `top channels caps the count and breaks ties by recency`() {
        val entries =
            (1..7).flatMap { n -> listOf(entry("x$n", daysAgo = n.toLong(), channel = "ch$n"), entry("y$n", daysAgo = n.toLong(), channel = "ch$n")) }
        assertEquals(setOf("ch1", "ch2", "ch3", "ch4", "ch5"), HistorySmartLists.topChannels(entries))
    }

    @Test
    fun `rows without a channel id group by name and blank channels are ignored`() {
        val named = entry("n1").copy(channelId = "", channelName = "Local")
        val named2 = entry("n2").copy(channelId = "", channelName = " Local ")
        val blank = entry("z1").copy(channelId = "", channelName = "")
        val blank2 = entry("z2").copy(channelId = "", channelName = "")
        assertEquals(setOf("Local"), HistorySmartLists.topChannels(listOf(named, named2, blank, blank2)))
    }
}
