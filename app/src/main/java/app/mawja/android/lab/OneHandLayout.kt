/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

/**
 * One-hand mode: on a tall fullscreen video the play, pause and skip buttons move down into the
 * bottom third of the screen, where a thumb holding the phone can reach them.
 */
object OneHandLayout {
    /** Vertical bias (-1 top .. 1 bottom) the transport buttons sit at in one-hand mode. */
    const val TRANSPORT_BIAS = 0.55f

    /** Where the transport buttons sit: lowered only in one-hand mode on a portrait fullscreen video. */
    fun transportBias(
        enabled: Boolean,
        isPortraitFullscreen: Boolean,
    ): Float = if (enabled && isPortraitFullscreen) TRANSPORT_BIAS else 0f

    /** Share of the screen height above the centre of the lowered buttons (0 top .. 1 bottom). */
    fun heightFraction(bias: Float): Float = (bias.coerceIn(-1f, 1f) + 1f) / 2f
}
