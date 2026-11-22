/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

/** UI sounds get quieter as the night goes on, so a tap at 2 am never startles anyone. */
object NightVolume {
    /** Multiplier for the UI-sound volume at [hourOfDay] (0..23). */
    fun factor(hourOfDay: Int): Float =
        when (hourOfDay) {
            in 6..21 -> 1f
            22, 5 -> 0.7f
            else -> 0.45f
        }
}
