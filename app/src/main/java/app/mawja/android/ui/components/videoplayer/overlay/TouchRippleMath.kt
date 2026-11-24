/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.videoplayer.overlay

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The circular wave a double-tap seek sends out from under the finger. It grows from the touch
 * point toward the farthest corner of the player and fades as it goes.
 */
internal object TouchRippleMath {
    /** How long one wave takes to spread, in milliseconds. */
    const val DURATION_MS = 520

    /** Share of the farthest-corner distance a wave reaches before it is gone. */
    const val REACH = 0.85f

    /** Opacity the wave starts at. */
    const val START_ALPHA = 0.32f

    /** Smallest radius, so the wave is visible under the fingertip on its very first frame. */
    const val MIN_RADIUS_PX = 12f

    /** Distance from ([x], [y]) to the farthest corner of a [width] x [height] box. */
    fun maxRadius(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): Float {
        if (width <= 0f || height <= 0f) return 0f
        val dx = max(x, width - x)
        val dy = max(y, height - y)
        return sqrt(dx * dx + dy * dy)
    }

    /** Radius at animation [progress] (0..1), easing out so the wave leaves fast and settles. */
    fun radiusAt(
        progress: Float,
        maxRadius: Float,
    ): Float {
        val p = progress.coerceIn(0f, 1f)
        val eased = 1f - (1f - p) * (1f - p)
        val reach = maxRadius * REACH
        return MIN_RADIUS_PX + (reach - MIN_RADIUS_PX).coerceAtLeast(0f) * eased
    }

    /** Opacity at animation [progress] (0..1): full at the start, gone at the end. */
    fun alphaAt(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return START_ALPHA * (1f - p)
    }
}
