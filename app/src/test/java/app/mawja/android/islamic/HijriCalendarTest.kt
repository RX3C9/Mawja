/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class HijriCalendarTest {
    @Test
    fun knownDates() {
        assertEquals(HijriDate(1445, 1, 1), HijriCalendar.fromGregorian(LocalDate.of(2023, 7, 19)))
        assertEquals(HijriDate(1445, 9, 1), HijriCalendar.fromGregorian(LocalDate.of(2024, 3, 11)))
        assertEquals(HijriDate(1447, 7, 26), HijriCalendar.fromGregorian(LocalDate.of(2026, 1, 15)))
        assertEquals(HijriDate(1447, 9, 26), HijriCalendar.fromGregorian(LocalDate.of(2026, 3, 15)))
    }

    @Test
    fun adjustmentShiftsByDaysAndIsClamped() {
        // Tabular is a day behind the published calendar on 1 Nov 2026 (21 Jumada al-Ula 1448).
        assertEquals(HijriDate(1448, 5, 20), HijriCalendar.fromGregorian(LocalDate.of(2026, 11, 1)))
        assertEquals(HijriDate(1448, 5, 21), HijriCalendar.fromGregorian(LocalDate.of(2026, 11, 1), 1))
        assertEquals(
            HijriCalendar.fromGregorian(LocalDate.of(2026, 11, 1), 2),
            HijriCalendar.fromGregorian(LocalDate.of(2026, 11, 1), 9),
        )
    }

    @Test
    fun roundTripsOverDecades() {
        var date = LocalDate.of(1990, 1, 1)
        while (date.isBefore(LocalDate.of(2060, 1, 1))) {
            val h = HijriCalendar.fromGregorian(date)
            assertEquals(date.toString(), date, HijriCalendar.toGregorian(h))
            assertEquals(true, h.day in 1..HijriCalendar.monthLength(h.year, h.month))
            date = date.plusDays(1)
        }
        assertEquals(LocalDate.of(2026, 11, 2), HijriCalendar.toGregorian(HijriDate(1448, 5, 22), adjustDays = 1))
    }

    @Test
    fun leapYearsHave355Days() {
        listOf(1442, 1445, 1447).forEach { y ->
            assertEquals(true, HijriCalendar.isLeap(y))
            assertEquals(355L, HijriCalendar.toGregorian(HijriDate(y + 1, 1, 1)).toEpochDay() - HijriCalendar.toGregorian(HijriDate(y, 1, 1)).toEpochDay())
        }
        assertEquals(354L, HijriCalendar.toGregorian(HijriDate(1449, 1, 1)).toEpochDay() - HijriCalendar.toGregorian(HijriDate(1448, 1, 1)).toEpochDay())
        assertEquals(30, HijriCalendar.monthLength(1447, 12))
        assertEquals(29, HijriCalendar.monthLength(1448, 12))
    }
}
