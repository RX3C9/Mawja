/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

/** How the week felt, from the daily average; drives the opening line of the shareable memo. */
enum class WeekPace { QUIET, STEADY, DEEP }

/** "Weekly memo": the small facts the shareable summary of your week is built from. */
object WeekMemo {
    fun pace(dailyAverageMinutes: Int): WeekPace =
        when {
            dailyAverageMinutes < 30 -> WeekPace.QUIET
            dailyAverageMinutes < 120 -> WeekPace.STEADY
            else -> WeekPace.DEEP
        }

    /** Days ago of the busiest day (0 = today), or null when nothing was watched. [perDay] is oldest first. */
    fun busiestDaysAgo(perDay: List<Int>): Int? {
        val max = perDay.maxOrNull() ?: return null
        if (max <= 0) return null
        return perDay.size - 1 - perDay.lastIndexOf(max)
    }
}
