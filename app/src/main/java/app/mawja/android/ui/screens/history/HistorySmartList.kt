package app.mawja.android.ui.screens.history

import app.mawja.android.data.local.VideoHistoryEntry

/**
 * Smart lists (#151) built from watch history alone, so they need no extra storage and update with
 * it. History keeps one row per video, so "played most" is read at the channel level: what you
 * watched from the channels you come back to most.
 */
enum class HistorySmartList {
    /** Started but not finished: past the first few seconds and short of the end. */
    Unfinished,

    /** Last played more than [HistorySmartLists.LONG_AGO_DAYS] days ago. */
    LongAgo,

    /** Everything from the [HistorySmartLists.TOP_CHANNELS] channels with the most watched videos. */
    TopChannels,

    /** Videos and tracks the viewer rated (#152), highest rating first, then most recently watched. */
    Rated,
}

object HistorySmartLists {
    const val LONG_AGO_DAYS = 60
    const val TOP_CHANNELS = 5
    const val UNFINISHED_MIN_PERCENT = 3f
    const val UNFINISHED_MAX_PERCENT = 90f
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    /**
     * Keeps [entries]' order; [list] null keeps them all. [ratings] is videoId → stars (1..5), used by
     * [HistorySmartList.Rated], which instead orders by rating, then most recently watched.
     */
    fun apply(
        list: HistorySmartList?,
        entries: List<VideoHistoryEntry>,
        now: Long,
        ratings: Map<String, Int> = emptyMap(),
    ): List<VideoHistoryEntry> =
        when (list) {
            null -> entries
            HistorySmartList.Unfinished -> entries.filter(::isUnfinished)
            HistorySmartList.LongAgo -> {
                val cutoff = now - LONG_AGO_DAYS * MILLIS_PER_DAY
                entries.filter { it.timestamp in 1 until cutoff }
            }
            HistorySmartList.TopChannels -> {
                val top = topChannels(entries)
                entries.filter { channelKey(it) in top }
            }
            HistorySmartList.Rated -> rated(entries, ratings)
        }

    /** Rated entries only, highest rating first, ties to the most recently watched. */
    internal fun rated(
        entries: List<VideoHistoryEntry>,
        ratings: Map<String, Int>,
    ): List<VideoHistoryEntry> =
        entries
            .filter { (ratings[it.videoId] ?: 0) > 0 }
            .sortedWith(
                compareByDescending<VideoHistoryEntry> { ratings[it.videoId] ?: 0 }
                    .thenByDescending { it.timestamp },
            )

    internal fun isUnfinished(entry: VideoHistoryEntry): Boolean =
        !entry.isShort &&
            entry.duration > 0 &&
            entry.progressPercentage >= UNFINISHED_MIN_PERCENT &&
            entry.progressPercentage < UNFINISHED_MAX_PERCENT

    /** Channels with at least two watched videos, most first; ties go to the most recently watched. */
    internal fun topChannels(entries: List<VideoHistoryEntry>): Set<String> =
        entries
            .groupBy(::channelKey)
            .filterKeys { it.isNotEmpty() }
            .filterValues { it.size >= 2 }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<VideoHistoryEntry>>> { it.value.size }
                    .thenByDescending { group -> group.value.maxOf { it.timestamp } },
            ).take(TOP_CHANNELS)
            .map { it.key }
            .toSet()

    /** The channel id, or its name for rows saved without one (local files have neither). */
    internal fun channelKey(entry: VideoHistoryEntry): String = entry.channelId.ifBlank { entry.channelName.trim() }
}
