/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.videoplayer.gesture

import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Travel that moves a held boost by one [HoldSpeedDrag.STEP]. */
internal val HoldSpeedStepTravel = 22.dp

/**
 * Press and hold boosts the speed; sliding the finger while still holding tunes it. Right or up is
 * faster, left or down is slower, in quarter steps between [MIN_SPEED] and [MAX_SPEED]. Whichever
 * axis the finger has travelled further along decides, so a slightly diagonal slide never jumps.
 */
internal object HoldSpeedDrag {
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 4.0f
    const val STEP = 0.25f

    /** Signed travel along the dominant axis: positive means faster. [dy] is screen-down positive. */
    fun travelOf(
        dx: Float,
        dy: Float,
    ): Float = if (abs(dx) >= abs(dy)) dx else -dy

    /** The speed for a hold that boosted to [boostSpeed] and has since moved by [dx]/[dy] pixels. */
    fun speedFor(
        boostSpeed: Float,
        dx: Float,
        dy: Float,
        stepTravelPx: Float,
    ): Float {
        val start = boostSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        return PlayerSpeedSwipe.speedFor(
            startSpeed = start,
            travelUpPx = travelOf(dx, dy),
            stepTravelPx = stepTravelPx,
            step = STEP,
            minSpeed = MIN_SPEED,
            maxSpeed = MAX_SPEED,
        )
    }
}
