/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

/** "Ends at 11:42": the wall-clock time a video finishes, at the speed it is playing. */
object EndsAt {
    fun endMillis(
        nowMs: Long,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
    ): Long? {
        if (durationMs <= 0L) return null
        val left = (durationMs - positionMs).coerceAtLeast(0L)
        val rate = if (speed.isFinite() && speed > 0f) speed else 1f
        return nowMs + (left / rate).toLong()
    }
}
