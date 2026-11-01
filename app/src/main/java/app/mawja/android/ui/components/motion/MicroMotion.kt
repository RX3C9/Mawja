/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.motion

/**
 * Pure timing rules for Mawja's small motion touches. Every rule returns the resting value under
 * reduce motion, so nothing moves when the user (or battery saver) asked for stillness.
 */
object MicroMotion {
    /** The like and download capsules sink to this scale while pressed, then spring back. */
    const val PRESS_SCALE = 0.86f

    fun pressScale(
        pressed: Boolean,
        reduceMotion: Boolean,
    ): Float = if (pressed && !reduceMotion) PRESS_SCALE else 1f

    /** The full player grows from this scale to 1 as it opens. */
    const val PLAYER_OPEN_START_SCALE = 0.94f

    /** Scale for the full player at [openFraction] (0 closed, 1 open). */
    fun playerOpenScale(
        openFraction: Float,
        reduceMotion: Boolean,
    ): Float {
        if (reduceMotion) return 1f
        val f = openFraction.coerceIn(0f, 1f)
        return PLAYER_OPEN_START_SCALE + (1f - PLAYER_OPEN_START_SCALE) * f
    }
}

/**
 * First-load stagger for feed lists: the first [MAX_ITEMS] items fade in [STEP_MILLIS] apart, each
 * over [DURATION_MILLIS]. Later items, any item once the first load has played, and every item
 * under reduce motion appear at once (null delay).
 */
object StaggerTiming {
    const val MAX_ITEMS = 8
    const val STEP_MILLIS = 45
    const val DURATION_MILLIS = 240

    /** Upward drift in dp an item fades in from. */
    const val RISE_DP = 12f

    fun delayMillis(
        index: Int,
        firstLoadPlaying: Boolean,
        reduceMotion: Boolean,
    ): Int? =
        if (reduceMotion || !firstLoadPlaying || index < 0 || index >= MAX_ITEMS) {
            null
        } else {
            index * STEP_MILLIS
        }

    /** How long the whole first-load cascade lasts; after this the list stops staggering. */
    val totalMillis: Int get() = (MAX_ITEMS - 1) * STEP_MILLIS + DURATION_MILLIS
}
