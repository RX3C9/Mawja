package app.mawja.android.ui.screens.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HistoryTimelineTest {
    private val cairo = TimeZone.getTimeZone("Africa/Cairo")

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
    ): Long =
        Calendar
            .getInstance(cairo)
            .apply {
                clear()
                set(year, month - 1, day, hour, 0)
            }.timeInMillis

    @Test
    fun `day sections start at local midnight`() {
        assertEquals(at(2026, 11, 4, 0), HistoryTimeline.sectionStart(at(2026, 11, 4, 23), HistoryGrouping.Day, Calendar.SATURDAY, cairo))
    }

    @Test
    fun `week sections start on the locale's first day`() {
        // Wednesday 4 November 2026.
        val wednesday = at(2026, 11, 4, 18)
        assertEquals(at(2026, 10, 31, 0), HistoryTimeline.startOfWeek(wednesday, Calendar.SATURDAY, cairo))
        assertEquals(at(2026, 11, 1, 0), HistoryTimeline.startOfWeek(wednesday, Calendar.SUNDAY, cairo))
        assertEquals(at(2026, 11, 2, 0), HistoryTimeline.startOfWeek(wednesday, Calendar.MONDAY, cairo))
    }

    @Test
    fun `the first day of a week is its own start`() {
        assertEquals(at(2026, 10, 31, 0), HistoryTimeline.startOfWeek(at(2026, 10, 31, 0), Calendar.SATURDAY, cairo))
    }

    @Test
    fun `weeks ago counts whole weeks back from now`() {
        val now = at(2026, 11, 4)
        assertEquals(0, HistoryTimeline.weeksAgo(at(2026, 10, 31, 0), now, Calendar.SATURDAY, cairo))
        assertEquals(1, HistoryTimeline.weeksAgo(at(2026, 10, 24, 0), now, Calendar.SATURDAY, cairo))
        assertEquals(5, HistoryTimeline.weeksAgo(HistoryTimeline.startOfWeek(at(2026, 9, 30), Calendar.SATURDAY, cairo), now, Calendar.SATURDAY, cairo))
    }

    @Test
    fun `a week across a clock change still counts as one week`() {
        // Egypt left summer time on the last Thursday of October 2026.
        val now = at(2026, 11, 4)
        val before = HistoryTimeline.startOfWeek(at(2026, 10, 27), Calendar.SATURDAY, cairo)
        assertEquals(1, HistoryTimeline.weeksAgo(before, now, Calendar.SATURDAY, cairo))
    }
}
