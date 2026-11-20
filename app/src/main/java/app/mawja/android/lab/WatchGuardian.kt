/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

/**
 * "Time guardian": notices drifting (several autoplayed videos in a row with no choice made, or a
 * long unbroken session) and suggests a pause, gently and at most once per [cooldownMs].
 * Pure state machine; the UI feeds it events and shows the card when [onEvent] returns true.
 */
class WatchGuardian(
    private val autoplayStreak: Int = 4,
    private val sessionMs: Long = 90 * 60_000L,
    private val cooldownMs: Long = 45 * 60_000L,
) {
    sealed interface Event {
        val atMs: Long

        data class Started(override val atMs: Long, val autoplayed: Boolean) : Event

        data class UserChose(override val atMs: Long) : Event

        data class Paused(override val atMs: Long, val forMs: Long) : Event
    }

    private var streak = 0
    private var sessionStart = -1L
    private var lastNudge = Long.MIN_VALUE / 2

    /** Feeds one event; true when this is the moment to suggest a break. */
    fun onEvent(e: Event): Boolean {
        when (e) {
            is Event.Started -> {
                if (sessionStart < 0) sessionStart = e.atMs
                streak = if (e.autoplayed) streak + 1 else 0
            }
            is Event.UserChose -> streak = 0
            is Event.Paused -> if (e.forMs >= 10 * 60_000L) { sessionStart = -1; streak = 0 }
        }
        val drifting = streak >= autoplayStreak || (sessionStart >= 0 && e.atMs - sessionStart >= sessionMs)
        if (drifting && e.atMs - lastNudge >= cooldownMs) {
            lastNudge = e.atMs
            streak = 0
            return true
        }
        return false
    }
}
