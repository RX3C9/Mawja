/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import org.junit.Assert.assertEquals
import org.junit.Test

class BedtimeFilterTest {
    @Test fun offDuringTheDay() = assertEquals(0f, BedtimeFilter.strength(12 * 60, 0), 0f)

    @Test fun rampsUpOverTheFirstHalfHour() {
        assertEquals(0f, BedtimeFilter.strength(0, 0), 0f)
        assertEquals(0.5f, BedtimeFilter.strength(15, 0), 0.001f)
        assertEquals(1f, BedtimeFilter.strength(30, 0), 0f)
    }

    @Test fun fullInTheMiddleOfTheNight() = assertEquals(1f, BedtimeFilter.strength(3 * 60, 0), 0f)

    @Test fun fadesOutTowardSixAm() {
        assertEquals(0.5f, BedtimeFilter.strength(5 * 60 + 45, 0), 0.001f)
        assertEquals(0f, BedtimeFilter.strength(6 * 60, 0), 0f)
    }

    @Test fun windowCanStartBeforeMidnight() {
        val start = 22 * 60
        assertEquals(0f, BedtimeFilter.strength(21 * 60, start), 0f)
        assertEquals(1f, BedtimeFilter.strength(23 * 60 + 30, start), 0f)
        assertEquals(1f, BedtimeFilter.strength(2 * 60, start), 0f)
        assertEquals(0f, BedtimeFilter.strength(7 * 60, start), 0f)
    }

    @Test fun emptyWindowNeverTints() = assertEquals(0f, BedtimeFilter.strength(100, 360, 360), 0f)
}
