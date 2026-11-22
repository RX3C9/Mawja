/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeekMemoTest {
    @Test fun paceFollowsTheDailyAverage() {
        assertEquals(WeekPace.QUIET, WeekMemo.pace(0))
        assertEquals(WeekPace.QUIET, WeekMemo.pace(29))
        assertEquals(WeekPace.STEADY, WeekMemo.pace(30))
        assertEquals(WeekPace.STEADY, WeekMemo.pace(119))
        assertEquals(WeekPace.DEEP, WeekMemo.pace(120))
    }

    @Test fun busiestDayIsCountedBackFromToday() {
        assertEquals(0, WeekMemo.busiestDaysAgo(listOf(1, 2, 3, 4, 5, 6, 40)))
        assertEquals(3, WeekMemo.busiestDaysAgo(listOf(1, 2, 3, 90, 5, 6, 7)))
    }

    @Test fun aTieGoesToTheMostRecentDay() = assertEquals(1, WeekMemo.busiestDaysAgo(listOf(0, 0, 0, 0, 50, 50, 0)))

    @Test fun nothingWatchedHasNoBusiestDay() {
        assertNull(WeekMemo.busiestDaysAgo(listOf(0, 0, 0, 0, 0, 0, 0)))
        assertNull(WeekMemo.busiestDaysAgo(emptyList()))
    }
}
