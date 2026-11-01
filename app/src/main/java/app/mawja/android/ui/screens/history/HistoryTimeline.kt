package app.mawja.android.ui.screens.history

import java.util.Calendar
import java.util.TimeZone

/** How the history timeline (#149) is split into sections: one per day, or one per week. */
enum class HistoryGrouping {
    Day,
    Week,
}

object HistoryTimeline {
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    /** Midnight at the start of [timestamp]'s day in [zone]. */
    fun startOfDay(
        timestamp: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long =
        Calendar
            .getInstance(zone)
            .apply {
                timeInMillis = timestamp
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

    /**
     * Midnight on the first day of [timestamp]'s week, where a week starts on [firstDayOfWeek]
     * (a [Calendar] day constant: Saturday in much of the Arab world, Sunday or Monday elsewhere).
     */
    fun startOfWeek(
        timestamp: Long,
        firstDayOfWeek: Int = Calendar.getInstance().firstDayOfWeek,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = startOfDay(timestamp, zone) }
        val back = (calendar.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
        calendar.add(Calendar.DAY_OF_MONTH, -back)
        return calendar.timeInMillis
    }

    /** The section a row falls in: the start of its day or of its week. */
    fun sectionStart(
        timestamp: Long,
        grouping: HistoryGrouping,
        firstDayOfWeek: Int = Calendar.getInstance().firstDayOfWeek,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long =
        when (grouping) {
            HistoryGrouping.Day -> startOfDay(timestamp, zone)
            HistoryGrouping.Week -> startOfWeek(timestamp, firstDayOfWeek, zone)
        }

    /**
     * Whole weeks between the week starting [weekStart] and the week holding [now]: 0 this week,
     * 1 last week. Rounded so a daylight-saving hour never tips it over.
     */
    fun weeksAgo(
        weekStart: Long,
        now: Long,
        firstDayOfWeek: Int = Calendar.getInstance().firstDayOfWeek,
        zone: TimeZone = TimeZone.getDefault(),
    ): Int {
        val thisWeek = startOfWeek(now, firstDayOfWeek, zone)
        return Math.round((thisWeek - weekStart).toDouble() / (7 * MILLIS_PER_DAY)).toInt()
    }
}
