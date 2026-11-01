/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PrayerScheduleTest {
    private val h = 3_600_000L
    private val today =
        PrayerDay(
            LocalDate.of(2026, 11, 1),
            mapOf(Prayer.FAJR to 5 * h, Prayer.SUNRISE to 6 * h, Prayer.DHUHR to 12 * h, Prayer.ASR to 15 * h, Prayer.MAGHRIB to 17 * h, Prayer.ISHA to 19 * h),
        )
    private val tomorrow = PrayerDay(LocalDate.of(2026, 11, 2), today.times.mapValues { it.value + 24 * h })

    @Test
    fun nextSkipsSunriseAndWrapsToTomorrow() {
        assertEquals(UpcomingPrayer(Prayer.DHUHR, 12 * h), PrayerSchedule.next(5 * h + 1, today, tomorrow))
        assertEquals(UpcomingPrayer(Prayer.FAJR, 29 * h), PrayerSchedule.next(20 * h, today, tomorrow))
        assertEquals(UpcomingPrayer(Prayer.FAJR, 5 * h), PrayerSchedule.next(0, today, tomorrow))
    }

    @Test
    fun currentPrayerWithinWindow() {
        assertEquals(Prayer.ASR, PrayerSchedule.current(15 * h + 5 * 60_000L, today))
        assertNull(PrayerSchedule.current(15 * h + 25 * 60_000L, today))
        assertNull(PrayerSchedule.current(6 * h + 60_000L, today)) // sunrise is not a prayer
    }

    @Test
    fun lateAlarmsDoNotPause() {
        assertTrue(PrayerSchedule.isTimely(12 * h + 3 * 60_000L, 12 * h))
        assertFalse(PrayerSchedule.isTimely(12 * h + 40 * 60_000L, 12 * h))
        assertFalse(PrayerSchedule.isTimely(12 * h - 5 * 60_000L, 12 * h))
    }

    @Test
    fun countdownRoundsUp() {
        assertEquals(1 to 1, PrayerSchedule.countdown(0, h + 30_000L))
        assertEquals(0 to 1, PrayerSchedule.countdown(0, 1_000L))
        assertEquals(0 to 0, PrayerSchedule.countdown(10, 0))
    }

    @Test
    fun adhanModeKeys() {
        assertEquals(AdhanMode.OFF, AdhanMode.fromKey(null))
        assertEquals(AdhanMode.PAUSE, AdhanMode.fromKey("pause"))
    }
}
