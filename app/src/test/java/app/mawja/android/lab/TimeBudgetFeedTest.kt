/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import app.mawja.android.data.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeBudgetFeedTest {
    private fun v(
        id: String,
        seconds: Int,
        short: Boolean = false,
        live: Boolean = false,
    ) = Video(id, id, "c", "c", "", seconds, 0L, uploadDate = "", isShort = short, isLive = live)

    @Test fun fitsTheBudget() {
        val feed = listOf(v("a", 600), v("b", 300), v("c", 420), v("d", 180))
        val picked = TimeBudgetFeed.plan(feed, 15)
        assertTrue(picked.sumOf { it.duration } <= 900)
        assertTrue(picked.isNotEmpty())
    }

    @Test fun skipsShortsLivesAndUnknownLength() {
        val feed = listOf(v("s", 30, short = true), v("l", 100, live = true), v("z", 0), v("ok", 120))
        assertEquals(listOf("ok"), TimeBudgetFeed.plan(feed, 5).map { it.id })
    }

    @Test fun prefersHigherRankedWhenOnlyOneFits() {
        val feed = listOf(v("top", 500), v("low", 500))
        assertEquals(listOf("top"), TimeBudgetFeed.plan(feed, 10).map { it.id })
    }

    @Test fun emptyWhenNothingFits() {
        assertTrue(TimeBudgetFeed.plan(listOf(v("long", 4000)), 5).isEmpty())
    }

    @Test fun surprisePicksFromLowerHalf() {
        val feed = (1..10).map { v("v$it", 100) }
        val picked = TimeBudgetFeed.surprise(feed, 3, kotlin.random.Random(7)).map { it.id }
        assertEquals(3, picked.size)
        assertTrue(picked.all { it.removePrefix("v").toInt() > 5 })
    }
}
