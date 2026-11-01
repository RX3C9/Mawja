/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.widget.prayer

import app.mawja.android.islamic.Prayer
import app.mawja.android.islamic.PrayerDay
import app.mawja.android.islamic.PrayerSchedule
import app.mawja.android.islamic.UpcomingPrayer

/** One of the day's five prayers as the widget lists it. */
data class PrayerSlot(
    val prayer: Prayer,
    val atMillis: Long,
    val isNext: Boolean,
)

/** What the Next Prayer widget shows. Pure: built from the clock and the day's times only. */
sealed interface NextPrayerState {
    /** The optional prayer-times section is turned off in Settings. */
    data object Off : NextPrayerState

    /** The section is on but there is no city or location to calculate from. */
    data object NoLocation : NextPrayerState

    /** Counting down to [next]; [soon] once it is [NextPrayerModel.SOON_MS] or less away. */
    data class Upcoming(
        val next: UpcomingPrayer,
        val soon: Boolean,
        val slots: List<PrayerSlot>,
    ) : NextPrayerState

    /** [prayer]'s time came within [PrayerSchedule.CURRENT_WINDOW_MS]; [next] follows it. */
    data class Now(
        val prayer: Prayer,
        val atMillis: Long,
        val next: UpcomingPrayer?,
        val slots: List<PrayerSlot>,
    ) : NextPrayerState
}

object NextPrayerModel {
    /** "Soon" styling starts this long before a prayer. */
    const val SOON_MS = 15 * 60_000L

    /** Never sleep longer than this, so a missed alarm or a date change heals itself. */
    const val MAX_SLEEP_MS = 6 * 60 * 60_000L

    fun state(
        now: Long,
        enabled: Boolean,
        today: PrayerDay?,
        tomorrow: PrayerDay?,
    ): NextPrayerState {
        if (!enabled) return NextPrayerState.Off
        if (today == null || tomorrow == null) return NextPrayerState.NoLocation
        val next = PrayerSchedule.next(now, today, tomorrow)
        val current = PrayerSchedule.current(now, today)
        if (current != null) {
            val at = today[current] ?: now
            // While a prayer is "now", the list stays on its own day and highlights it.
            return NextPrayerState.Now(current, at, next, slots(today, current))
        }
        if (next == null) return NextPrayerState.NoLocation
        // After Isha the next prayer is tomorrow's Fajr: list tomorrow's times then.
        val listed = if (tomorrow[next.prayer] == next.atMillis && today[next.prayer] != next.atMillis) tomorrow else today
        return NextPrayerState.Upcoming(next, next.atMillis - now <= SOON_MS, slots(listed, next.prayer))
    }

    /**
     * When the widget should next redraw: [SOON_MS] before the next prayer, at it, and when the
     * "now" window of the current one ends. Null when nothing will change on its own.
     */
    fun nextRefresh(
        now: Long,
        state: NextPrayerState,
    ): Long? {
        val candidates =
            when (state) {
                NextPrayerState.Off, NextPrayerState.NoLocation -> return null
                is NextPrayerState.Upcoming -> listOf(state.next.atMillis - SOON_MS, state.next.atMillis)
                is NextPrayerState.Now ->
                    listOfNotNull(
                        state.atMillis + PrayerSchedule.CURRENT_WINDOW_MS,
                        state.next?.let { it.atMillis - SOON_MS },
                        state.next?.atMillis,
                    )
            }
        val soonest = candidates.filter { it > now }.minOrNull() ?: (now + MAX_SLEEP_MS)
        return minOf(soonest, now + MAX_SLEEP_MS)
    }

    private fun slots(
        day: PrayerDay,
        highlighted: Prayer,
    ): List<PrayerSlot> =
        Prayer.entries
            .filter { it.isPrayer }
            .mapNotNull { p -> day[p]?.let { PrayerSlot(p, it, p == highlighted) } }
}
