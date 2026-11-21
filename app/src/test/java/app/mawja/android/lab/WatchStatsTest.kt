/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import app.mawja.android.data.local.VideoHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchStatsTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun e(
        id: String,
        posMin: Long,
        durMin: Long,
        ago: Long,
        ch: String = "c",
        music: Boolean = false,
        short: Boolean = false,
    ) = VideoHistoryEntry(id, posMin * 60_000, durMin * 60_000, now - ago, id, "", ch, "", music, short)

    @Test fun sumsOnlyThisWeekAndCapsAtLength() {
        val w = WatchStats.week(listOf(e("a", 10, 20, day), e("b", 50, 30, 2 * day), e("old", 99, 99, 9 * day)), now)
        assertEquals(40, w.minutes)
        assertEquals(2, w.videos)
        assertEquals(5, w.dailyAverageMinutes)
    }

    @Test fun topChannelAndShares() {
        val w =
            WatchStats.week(
                listOf(e("a", 30, 30, day, "A", music = true), e("b", 10, 10, day, "B", short = true)),
                now,
            )
        assertEquals("A", w.topChannel)
        assertEquals(75, w.musicPercent)
        assertEquals(25, w.shortsPercent)
    }

    @Test fun emptyHistory() {
        val w = WatchStats.week(emptyList(), now)
        assertEquals(0, w.minutes)
        assertNull(w.topChannel)
    }

    @Test fun perDayBuckets() {
        val w = WatchStats.week(listOf(e("today", 5, 10, 1000), e("y", 7, 10, day + 1000)), now)
        assertEquals(7, w.perDay.size)
        assertEquals(5, w.perDay[6])
        assertEquals(7, w.perDay[5])
    }
}
