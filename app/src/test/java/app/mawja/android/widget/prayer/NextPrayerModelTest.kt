package app.mawja.android.widget.prayer

import app.mawja.android.islamic.Prayer
import app.mawja.android.islamic.PrayerDay
import app.mawja.android.islamic.PrayerSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NextPrayerModelTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    // A day starting at 0: Fajr 5:00, Sunrise 6:30, Dhuhr 12:00, Asr 15:30, Maghrib 18:00, Isha 19:30.
    private val today =
        PrayerDay(
            LocalDate.of(2026, 11, 1),
            mapOf(
                Prayer.FAJR to 5 * hour,
                Prayer.SUNRISE to 6 * hour + 30 * min,
                Prayer.DHUHR to 12 * hour,
                Prayer.ASR to 15 * hour + 30 * min,
                Prayer.MAGHRIB to 18 * hour,
                Prayer.ISHA to 19 * hour + 30 * min,
            ),
        )
    private val tomorrow = PrayerDay(LocalDate.of(2026, 11, 2), today.times.mapValues { it.value + day })

    private fun state(now: Long) = NextPrayerModel.state(now, enabled = true, today = today, tomorrow = tomorrow)

    @Test
    fun offWhenTheSectionIsOff() {
        assertEquals(NextPrayerState.Off, NextPrayerModel.state(0L, enabled = false, today = today, tomorrow = tomorrow))
        assertNull(NextPrayerModel.nextRefresh(0L, NextPrayerState.Off))
    }

    @Test
    fun noLocationWithoutTimes() {
        assertEquals(NextPrayerState.NoLocation, NextPrayerModel.state(0L, enabled = true, today = null, tomorrow = null))
        assertNull(NextPrayerModel.nextRefresh(0L, NextPrayerState.NoLocation))
    }

    @Test
    fun countsDownToTheNextPrayerAndSkipsSunrise() {
        val s = state(7 * hour) as NextPrayerState.Upcoming
        assertEquals(Prayer.DHUHR, s.next.prayer)
        assertFalse(s.soon)
        assertEquals(5, s.slots.size)
        assertTrue(s.slots.none { it.prayer == Prayer.SUNRISE })
        assertEquals(listOf(Prayer.DHUHR), s.slots.filter { it.isNext }.map { it.prayer })
    }

    @Test
    fun soonWithinFifteenMinutes() {
        assertTrue((state(12 * hour - 15 * min) as NextPrayerState.Upcoming).soon)
        assertFalse((state(12 * hour - 16 * min) as NextPrayerState.Upcoming).soon)
    }

    @Test
    fun nowForTwentyMinutesAfterThePrayer() {
        val s = state(12 * hour + 5 * min) as NextPrayerState.Now
        assertEquals(Prayer.DHUHR, s.prayer)
        assertEquals(Prayer.ASR, s.next?.prayer)
        assertTrue(state(12 * hour + PrayerSchedule.CURRENT_WINDOW_MS) is NextPrayerState.Upcoming)
    }

    @Test
    fun afterIshaListsTomorrow() {
        val s = state(21 * hour) as NextPrayerState.Upcoming
        assertEquals(Prayer.FAJR, s.next.prayer)
        assertEquals(tomorrow[Prayer.FAJR], s.next.atMillis)
        assertEquals(tomorrow[Prayer.FAJR], s.slots.first { it.isNext }.atMillis)
    }

    @Test
    fun refreshesFifteenMinutesBeforeAtAndAfterThePrayer() {
        val noon = 12 * hour
        assertEquals(noon - 15 * min, NextPrayerModel.nextRefresh(7 * hour, state(7 * hour)))
        val soon = noon - 10 * min
        assertEquals(noon, NextPrayerModel.nextRefresh(soon, state(soon)))
        val now = noon + 1 * min
        assertEquals(noon + PrayerSchedule.CURRENT_WINDOW_MS, NextPrayerModel.nextRefresh(now, state(now)))
    }

    @Test
    fun neverSleepsLongerThanTheCap() {
        val late = 20 * hour
        assertEquals(late + NextPrayerModel.MAX_SLEEP_MS, NextPrayerModel.nextRefresh(late, state(late)))
    }
}
