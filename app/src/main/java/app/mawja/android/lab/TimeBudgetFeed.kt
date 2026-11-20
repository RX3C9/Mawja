/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import app.mawja.android.data.model.Video

/** Turns the Home feed into a "watch in N minutes" queue with [TimeBudgetPlanner]. */
object TimeBudgetFeed {
    val MINUTE_CHOICES = listOf(5, 10, 15, 30, 45, 60)

    /**
     * Higher in the feed scores higher (the feed is already ranked for the viewer). Shorts, lives,
     * premieres and videos with no known length are skipped: none of them has a length to plan with.
     */
    fun plan(
        feed: List<Video>,
        minutes: Int,
    ): List<Video> {
        val usable =
            feed
                .filter { !it.isShort && !it.isLive && !it.isUpcoming && it.duration > 0 }
                .distinctBy { it.id }
                .take(MAX_CANDIDATES)
        if (usable.isEmpty() || minutes <= 0) return emptyList()
        val n = usable.size
        val candidates = usable.mapIndexed { i, v -> BudgetCandidate(v.id, v.duration, (n - i).toDouble() / n) }
        val plan = TimeBudgetPlanner.plan(candidates, minutes * SECONDS_PER_MINUTE)
        val byId = usable.associateBy { it.id }
        return plan.ids.mapNotNull { byId[it] }
    }

    /** "Surprise me": a few videos from the lower half of the feed, to step outside the usual bubble. */
    fun surprise(
        feed: List<Video>,
        count: Int = 5,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): List<Video> {
        val usable = feed.filter { !it.isShort && !it.isLive && !it.isUpcoming }.distinctBy { it.id }
        if (usable.isEmpty()) return emptyList()
        val pool = usable.drop(usable.size / 2).ifEmpty { usable }
        return pool.shuffled(random).take(count)
    }

    private const val MAX_CANDIDATES = 120
    private const val SECONDS_PER_MINUTE = 60
}
