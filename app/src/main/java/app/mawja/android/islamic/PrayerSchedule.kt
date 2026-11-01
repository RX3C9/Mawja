/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

/** What an adhan does to Mawja: nothing, a notification, or a notification and a pause. */
enum class AdhanMode(
    val key: String,
) {
    OFF("off"),
    NOTIFY("notify"),
    PAUSE("pause"),
    ;

    companion object {
        fun fromKey(key: String?): AdhanMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}

data class UpcomingPrayer(
    val prayer: Prayer,
    val atMillis: Long,
)

/** Pure decisions about the day's prayers: which comes next, which one is now, when to wake up. */
object PrayerSchedule {
    /** A prayer counts as "now" for this long after its time (the notification still makes sense). */
    const val CURRENT_WINDOW_MS = 20 * 60_000L

    /** The next real prayer (not sunrise) strictly after [now], looking at [today] then [tomorrow]. */
    fun next(
        now: Long,
        today: PrayerDay,
        tomorrow: PrayerDay,
    ): UpcomingPrayer? =
        sequenceOf(today, tomorrow)
            .flatMap { day -> Prayer.entries.asSequence().filter { it.isPrayer }.mapNotNull { p -> day[p]?.let { UpcomingPrayer(p, it) } } }
            .filter { it.atMillis > now }
            .minByOrNull { it.atMillis }

    /** The prayer whose time came within the last [CURRENT_WINDOW_MS], if any. */
    fun current(
        now: Long,
        today: PrayerDay,
    ): Prayer? =
        Prayer.entries
            .filter { it.isPrayer }
            .lastOrNull { p -> today[p]?.let { now >= it && now - it < CURRENT_WINDOW_MS } == true }

    /**
     * Whether an alarm that fired at [now] for [scheduledAt] is still worth acting on. Doze can
     * hold an inexact alarm back; a pause half an hour late would only be confusing.
     */
    fun isTimely(
        now: Long,
        scheduledAt: Long,
    ): Boolean = now >= scheduledAt - 60_000L && now - scheduledAt < CURRENT_WINDOW_MS

    /** Splits a countdown into hours and minutes, rounding up so "in 0 min" never shows early. */
    fun countdown(
        now: Long,
        at: Long,
    ): Pair<Int, Int> {
        val minutes = ((at - now).coerceAtLeast(0L) + 59_999L) / 60_000L
        return (minutes / 60).toInt() to (minutes % 60).toInt()
    }
}
