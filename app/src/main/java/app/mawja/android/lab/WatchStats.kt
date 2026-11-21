/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import app.mawja.android.data.local.VideoHistoryEntry

/** "Your week on Mawja", computed on the phone from watch history; nothing leaves the device. */
data class WatchWeek(
    val minutes: Int,
    val videos: Int,
    val dailyAverageMinutes: Int,
    val topChannel: String?,
    val musicPercent: Int,
    val shortsPercent: Int,
    /** Minutes per day, oldest first; the last one is today (the 24 hours up to now). */
    val perDay: List<Int> = List(7) { 0 },
)

object WatchStats {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val WEEK_DAYS = 7

    /** History keeps the last position per video, so minutes are a close lower estimate. */
    fun week(
        history: List<VideoHistoryEntry>,
        nowMs: Long,
    ): WatchWeek {
        val since = nowMs - WEEK_DAYS * DAY_MS
        val recent = history.filter { it.timestamp in since..nowMs }.distinctBy { it.videoId }
        val watchedMs =
            recent.map { e -> if (e.duration > 0) e.position.coerceIn(0, e.duration) else e.position.coerceAtLeast(0) }
        val totalMs = watchedMs.sum()
        val minutes = (totalMs / 60_000L).toInt()
        val top =
            recent
                .zip(watchedMs)
                .filter { it.first.channelName.isNotBlank() }
                .groupBy { it.first.channelName }
                .maxByOrNull { (_, rows) -> rows.sumOf { it.second } }
                ?.key
        fun share(pick: (VideoHistoryEntry) -> Boolean): Int {
            if (totalMs <= 0L) return 0
            val part = recent.zip(watchedMs).filter { pick(it.first) }.sumOf { it.second }
            return ((part * 100) / totalMs).toInt()
        }
        return WatchWeek(
            minutes = minutes,
            videos = recent.size,
            dailyAverageMinutes = minutes / WEEK_DAYS,
            topChannel = top,
            musicPercent = share { it.isMusic },
            shortsPercent = share { it.isShort },
            perDay =
                List(WEEK_DAYS) { d ->
                    val end = nowMs - (WEEK_DAYS - 1 - d) * DAY_MS
                    val start = end - DAY_MS
                    (recent.zip(watchedMs).filter { it.first.timestamp > start && it.first.timestamp <= end }.sumOf { it.second } / 60_000L).toInt()
                },
        )
    }
}
