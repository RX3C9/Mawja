/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.core

/** Mawja 2.0 moderation rules shared by the admin UI and its tests (the Firestore rules enforce them). */
object ModerationActions {
    const val HOUR_MS = 3_600_000L
    const val AUTO_BAN_WARNINGS = 3
    const val MAX_WARNINGS = 10
    const val MAX_MESSAGE = 1000

    /** Quick choices for a temporary ban or a mute, in hours. */
    val DURATIONS_H = listOf(1, 24, 24 * 7)

    /** Feature ids the app understands (its membership features plus reports and codes). */
    val LOCKABLE =
        listOf(
            "premium_themes", "premium_icons", "ui_sounds", "visualizer", "cloud_sync",
            "family_sharing", "live_backgrounds", "cloud_ai", "reports", "codes",
        )

    data class Warning(
        val warnings: Int,
        /** The third warning bans the account automatically. */
        val autoBan: Boolean,
    )

    fun warn(current: Int): Warning {
        val next = (current.coerceAtLeast(0) + 1).coerceAtMost(MAX_WARNINGS)
        return Warning(next, next >= AUTO_BAN_WARNINGS)
    }

    /** End of a ban or mute [hours] from [now]; custom lengths are days * 24. */
    fun until(
        now: Long,
        hours: Int,
    ): Long = now + hours.coerceIn(1, 24 * 3650) * HOUR_MS

    /** A custom length typed as days; null when it is not a whole number from 1 to 3650. */
    fun customDays(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in 1..3650 }

    /** A private message id that changes with every new message (the app shows each id once). */
    fun messageId(now: Long): String = "m$now"

    fun messageOk(text: String): Boolean = text.isNotBlank() && text.length <= MAX_MESSAGE
}
