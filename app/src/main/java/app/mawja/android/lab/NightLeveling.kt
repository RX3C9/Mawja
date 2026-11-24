/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

/**
 * Bed mode volume leveling: late at night loud moments are held down and quiet speech is lifted, so
 * nobody reaches for the volume between an explosion and a whisper. It follows the same window as
 * the bedtime tint, easing in over the first half hour, and works in a few fixed steps so the audio
 * effect is only retuned when the step changes.
 */
object NightLeveling {
    /** Limiter settings for the audio effect, in the units the platform takes (dB, ratio, ms). */
    data class Settings(
        val thresholdDb: Float,
        val ratio: Float,
        val postGainDb: Float,
        val attackMs: Float,
        val releaseMs: Float,
    )

    /** How many strength steps the leveling moves through. */
    const val STEPS = 4

    private const val THRESHOLD_LIGHT_DB = -10f
    private const val THRESHOLD_DEEP_DB = -20f
    private const val RATIO_LIGHT = 4f
    private const val RATIO_DEEP = 10f
    private const val POST_GAIN_LIGHT_DB = 2f
    private const val POST_GAIN_DEEP_DB = 8f
    private const val ATTACK_MS = 1f
    private const val RELEASE_MS = 80f

    /** Strength (0..1) at [minuteOfDay] for a window that starts at [startHour], snapped to a step. */
    fun strength(
        minuteOfDay: Int,
        startHour: Int,
    ): Float {
        val raw = BedtimeFilter.strength(minuteOfDay, startHour * 60)
        if (raw <= 0f) return 0f
        val step = kotlin.math.ceil(raw * STEPS) / STEPS
        return step.coerceIn(0f, 1f)
    }

    /** The limiter for [strength], or null when leveling should be off. */
    fun settingsFor(strength: Float): Settings? {
        if (!strength.isFinite() || strength <= 0f) return null
        val s = strength.coerceAtMost(1f)
        return Settings(
            thresholdDb = lerp(THRESHOLD_LIGHT_DB, THRESHOLD_DEEP_DB, s),
            ratio = lerp(RATIO_LIGHT, RATIO_DEEP, s),
            postGainDb = lerp(POST_GAIN_LIGHT_DB, POST_GAIN_DEEP_DB, s),
            attackMs = ATTACK_MS,
            releaseMs = RELEASE_MS,
        )
    }

    /** Loudest level that comes out once leveled, in dBFS: the threshold plus the lift after it. */
    fun ceilingDb(settings: Settings): Float = settings.thresholdDb + settings.postGainDb

    private fun lerp(
        a: Float,
        b: Float,
        t: Float,
    ): Float = a + (b - a) * t
}
