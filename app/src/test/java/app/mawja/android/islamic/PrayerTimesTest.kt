/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Reference values: api.aladhan.com timings (same astronomical method), fetched 2026-11-01.
 * Every time must be within ±2 minutes.
 */
class PrayerTimesTest {
    private fun minutesOfDay(
        millis: Long,
        zone: ZoneId,
    ): Int {
        val t = Instant.ofEpochMilli(millis).atZone(zone)
        return t.hour * 60 + t.minute
    }

    private fun hm(s: String): Int = s.substringBefore(':').toInt() * 60 + s.substringAfter(':').toInt()

    private fun assertDay(
        date: LocalDate,
        zoneId: String,
        lat: Double,
        lng: Double,
        params: PrayerParams,
        expected: List<String>,
    ) {
        val zone = ZoneId.of(zoneId)
        val day = PrayerTimes.forDate(date, zone, lat, lng, params)
        Prayer.entries.zip(expected).forEach { (prayer, want) ->
            val got = day[prayer]
            assertNotNull("$prayer missing", got)
            val diff = abs(minutesOfDay(got!!, zone) - hm(want))
            assertTrue("$prayer: got ${minutesOfDay(got, zone) / 60}:${minutesOfDay(got, zone) % 60}, want $want", diff <= 2)
        }
    }

    @Test
    fun cairoEgyptianAuthority() =
        assertDay(
            LocalDate.of(2026, 11, 1),
            "Africa/Cairo",
            30.0444,
            31.2357,
            PrayerParams(CalcMethod.EGYPTIAN),
            listOf("04:41", "06:09", "11:39", "14:46", "17:08", "18:27"),
        )

    @Test
    fun makkahUmmAlQura() =
        assertDay(
            LocalDate.of(2026, 11, 1),
            "Asia/Riyadh",
            21.4225,
            39.8262,
            PrayerParams(CalcMethod.UMM_AL_QURA),
            listOf("05:06", "06:24", "12:04", "15:20", "17:45", "19:15"),
        )

    @Test
    fun londonMuslimWorldLeagueMidsummerAngleBased() =
        assertDay(
            LocalDate.of(2026, 6, 21),
            "Europe/London",
            51.5074,
            -0.1278,
            PrayerParams(CalcMethod.MWL, highLatitude = HighLatitudeRule.ANGLE_BASED),
            listOf("02:31", "04:43", "13:02", "17:25", "21:22", "23:27"),
        )

    @Test
    fun karachiHanafiAsr() =
        assertDay(
            LocalDate.of(2026, 1, 15),
            "Asia/Karachi",
            24.8607,
            67.0011,
            PrayerParams(CalcMethod.KARACHI, asr = AsrMethod.HANAFI),
            listOf("05:58", "07:19", "12:41", "16:28", "18:04", "19:24"),
        )

    @Test
    fun newYorkIsnaAfterDaylightSaving() =
        assertDay(
            LocalDate.of(2026, 3, 15),
            "America/New_York",
            40.7128,
            -74.0060,
            PrayerParams(CalcMethod.ISNA),
            listOf("05:53", "07:08", "13:05", "16:25", "19:03", "20:18"),
        )

    @Test
    fun withoutHighLatitudeRuleLondonSummerFajrDoesNotExist() {
        val zone = ZoneId.of("Europe/London")
        val none = PrayerTimes.forDate(LocalDate.of(2026, 6, 21), zone, 51.5074, -0.1278, PrayerParams(CalcMethod.MWL, highLatitude = HighLatitudeRule.NONE))
        assertFalse(Prayer.FAJR in none.times)
        assertTrue(Prayer.DHUHR in none.times)
        listOf(HighLatitudeRule.MIDDLE_OF_NIGHT, HighLatitudeRule.ONE_SEVENTH, HighLatitudeRule.ANGLE_BASED).forEach { rule ->
            val day = PrayerTimes.forDate(LocalDate.of(2026, 6, 21), zone, 51.5074, -0.1278, PrayerParams(CalcMethod.MWL, highLatitude = rule))
            assertTrue(rule.name, Prayer.FAJR in day.times && Prayer.ISHA in day.times)
            assertTrue(rule.name, day[Prayer.FAJR]!! < day[Prayer.SUNRISE]!!)
        }
    }

    @Test
    fun manualOffsetsMoveOnlyThatPrayer() {
        val zone = ZoneId.of("Africa/Cairo")
        val date = LocalDate.of(2026, 11, 1)
        val base = PrayerTimes.forDate(date, zone, 30.0444, 31.2357, PrayerParams())
        val shifted = PrayerTimes.forDate(date, zone, 30.0444, 31.2357, PrayerParams(offsets = mapOf(Prayer.MAGHRIB to 3, Prayer.FAJR to -2)))
        assertEquals(3 * 60_000L, shifted[Prayer.MAGHRIB]!! - base[Prayer.MAGHRIB]!!)
        assertEquals(-2 * 60_000L, shifted[Prayer.FAJR]!! - base[Prayer.FAJR]!!)
        assertEquals(base[Prayer.DHUHR], shifted[Prayer.DHUHR])
    }

    @Test
    fun ummAlQuraIshaIsTwoHoursAfterMaghribInRamadan() {
        val params = PrayerParams(CalcMethod.UMM_AL_QURA)
        val normal = PrayerTimes.hours(2026, 3, 1, 21.4225, 39.8262, 3.0, params, ramadan = false)
        val ramadan = PrayerTimes.hours(2026, 3, 1, 21.4225, 39.8262, 3.0, params, ramadan = true)
        assertEquals(1.5, normal.getValue(Prayer.ISHA) - normal.getValue(Prayer.MAGHRIB), 1e-9)
        assertEquals(2.0, ramadan.getValue(Prayer.ISHA) - ramadan.getValue(Prayer.MAGHRIB), 1e-9)
    }

    @Test
    fun timesComeInOrder() {
        val zone = ZoneId.of("Africa/Cairo")
        var date = LocalDate.of(2026, 1, 1)
        repeat(365) {
            val day = PrayerTimes.forDate(date, zone, 30.0444, 31.2357, PrayerParams())
            val times = Prayer.entries.map { day[it]!! }
            assertEquals(date.toString(), times.sorted(), times)
            date = date.plusDays(1)
        }
    }

    @Test
    fun formatsHours() {
        assertEquals("04:41", PrayerTimes.format(4 + 41 / 60.0))
        assertEquals("00:00", PrayerTimes.format(23.9999))
        assertEquals("--:--", PrayerTimes.format(Double.NaN))
    }

    @Test
    fun defaultMethodFollowsCountry() {
        assertEquals(CalcMethod.EGYPTIAN, CalcMethod.defaultFor("EG"))
        assertEquals(CalcMethod.EGYPTIAN, CalcMethod.defaultFor(null))
        assertEquals(CalcMethod.UMM_AL_QURA, CalcMethod.defaultFor("sa"))
        assertEquals(CalcMethod.KARACHI, CalcMethod.defaultFor("PK"))
        assertEquals(CalcMethod.ISNA, CalcMethod.defaultFor("US"))
        assertEquals(CalcMethod.MWL, CalcMethod.defaultFor("GB"))
    }
}
