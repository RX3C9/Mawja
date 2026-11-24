/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.theme

import androidx.compose.ui.graphics.Color
import java.util.Calendar

/** The four moods of the Mawja time-of-day theme, each starting at [startMinute] (minutes after midnight). */
enum class DayPhase(
    val startMinute: Int,
) {
    DAWN(5 * 60),
    NOON(8 * 60),
    SUNSET(16 * 60 + 30),
    NIGHT(19 * 60),
}

/**
 * Mawja "Nile Glass" time-of-day theme ([ThemeMode.TIME_OF_DAY]): dawn rose, noon Nile turquoise,
 * sunset amber and moonlit night. When a phase begins, its colours are blended in from the previous
 * phase over [TRANSITION_MINUTES], so the app never jumps from one look to the next. Pure: the
 * caller passes the minute of the day, so every rule is unit-tested without a clock.
 */
object TimeOfDayTheme {
    const val MINUTES_PER_DAY = 24 * 60

    /** How long a new phase takes to blend in after it starts. */
    const val TRANSITION_MINUTES = 45

    /** The theme re-reads the clock in steps this long, so colours change at most every few minutes. */
    const val STEP_MINUTES = 5

    fun normalize(minuteOfDay: Int): Int = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    fun phaseAt(minuteOfDay: Int): DayPhase {
        val m = normalize(minuteOfDay)
        return when {
            m >= DayPhase.NIGHT.startMinute -> DayPhase.NIGHT
            m >= DayPhase.SUNSET.startMinute -> DayPhase.SUNSET
            m >= DayPhase.NOON.startMinute -> DayPhase.NOON
            m >= DayPhase.DAWN.startMinute -> DayPhase.DAWN
            else -> DayPhase.NIGHT
        }
    }

    fun previous(phase: DayPhase): DayPhase = DayPhase.entries[(phase.ordinal + DayPhase.entries.size - 1) % DayPhase.entries.size]

    /** Minutes since the current phase began, across midnight for the night phase. */
    fun minutesIntoPhase(minuteOfDay: Int): Int {
        val m = normalize(minuteOfDay)
        return normalize(m - phaseAt(m).startMinute)
    }

    /** 0 right as a phase starts (still the previous phase's colours) up to 1 once the blend is done. */
    fun blendAt(minuteOfDay: Int): Float = (minutesIntoPhase(minuteOfDay).toFloat() / TRANSITION_MINUTES).coerceIn(0f, 1f)

    /** [minuteOfDay] rounded down to the theme's [STEP_MINUTES] grid. */
    fun quantize(minuteOfDay: Int): Int {
        val m = normalize(minuteOfDay)
        return m - m % STEP_MINUTES
    }

    /** Minutes until the next step of the grid, at least one. */
    fun minutesToNextStep(minuteOfDay: Int): Int = (STEP_MINUTES - normalize(minuteOfDay) % STEP_MINUTES).coerceAtLeast(1)

    /** The device's local minute of the day. */
    fun nowMinute(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun pairFor(phase: DayPhase): PalettePair =
        when (phase) {
            DayPhase.DAWN -> Dawn
            DayPhase.NOON -> Noon
            DayPhase.SUNSET -> Sunset
            DayPhase.NIGHT -> Night
        }

    /** The palette shown at [minuteOfDay], blended between phases. */
    fun pairAt(minuteOfDay: Int): PalettePair {
        val phase = phaseAt(minuteOfDay)
        val t = blendAt(minuteOfDay)
        val to = pairFor(phase)
        if (t >= 1f) return to
        val from = pairFor(previous(phase))
        return PalettePair(
            id = to.id,
            light = blend(from.light, to.light, t),
            dark = blend(from.dark, to.dark, t),
        )
    }

    /** [t] of [to] over [from], role by role. */
    fun blend(
        from: PaletteSeed,
        to: PaletteSeed,
        t: Float,
    ): PaletteSeed =
        PaletteSeed(
            primary = mixColors(to.primary, from.primary, t),
            onPrimary = mixColors(to.onPrimary, from.onPrimary, t),
            secondary = mixColors(to.secondary, from.secondary, t),
            background = mixColors(to.background, from.background, t),
            surface = mixColors(to.surface, from.surface, t),
            onSurface = mixColors(to.onSurface, from.onSurface, t),
            onSurfaceVariant = mixColors(to.onSurfaceVariant, from.onSurfaceVariant, t),
            outline = mixColors(to.outline, from.outline, t),
            error = mixColors(to.error, from.error, t),
        )

    // The four phase palettes. Every variant clears the same contrast floors as the Mawja premium
    // palettes, and so does every blend between two neighbouring phases (TimeOfDayThemeTest).
    private val Dawn =
        PalettePair(
            id = "time-of-day",
            light =
                PaletteSeed(
                    primary = Color(0xFFA4466A),
                    onPrimary = Color(0xFFFFFFFF),
                    secondary = Color(0xFF6E5BA8),
                    background = Color(0xFFFFF8F6),
                    surface = Color(0xFFF8E9EC),
                    onSurface = Color(0xFF24141C),
                    onSurfaceVariant = Color(0xFF66505A),
                    outline = Color(0xFFE4CBD2),
                    error = Color(0xFFBA1A1A),
                ),
            dark =
                PaletteSeed(
                    primary = Color(0xFFFFA3BE),
                    onPrimary = Color(0xFF3E0A22),
                    secondary = Color(0xFFC2B2FF),
                    background = Color(0xFF151022),
                    surface = Color(0xFF221A33),
                    onSurface = Color(0xFFF7E9F0),
                    onSurfaceVariant = Color(0xFFCDB9C9),
                    outline = Color(0xFF3A2F4F),
                    error = Color(0xFFFFB4AB),
                ),
        )

    private val Noon =
        PalettePair(
            id = "time-of-day",
            light =
                PaletteSeed(
                    primary = Color(0xFF00677E),
                    onPrimary = Color(0xFFFFFFFF),
                    secondary = Color(0xFFB2551F),
                    background = Color(0xFFF5FBFD),
                    surface = Color(0xFFE3F1F6),
                    onSurface = Color(0xFF061820),
                    onSurfaceVariant = Color(0xFF435A63),
                    outline = Color(0xFFBCD4DD),
                    error = Color(0xFFBA1A1A),
                ),
            dark =
                PaletteSeed(
                    primary = Color(0xFF4FD8EB),
                    onPrimary = Color(0xFF00303A),
                    secondary = Color(0xFFFFB37A),
                    background = Color(0xFF08161F),
                    surface = Color(0xFF112532),
                    onSurface = Color(0xFFE6F4F9),
                    onSurfaceVariant = Color(0xFFA8C3CE),
                    outline = Color(0xFF24404F),
                    error = Color(0xFFFFB4AB),
                ),
        )

    private val Sunset =
        PalettePair(
            id = "time-of-day",
            light =
                PaletteSeed(
                    primary = Color(0xFFA34A00),
                    onPrimary = Color(0xFFFFFFFF),
                    secondary = Color(0xFF9C2F6A),
                    background = Color(0xFFFFF8F3),
                    surface = Color(0xFFF9E8DC),
                    onSurface = Color(0xFF26150A),
                    onSurfaceVariant = Color(0xFF68503F),
                    outline = Color(0xFFE6CDB9),
                    error = Color(0xFFBA1A1A),
                ),
            dark =
                PaletteSeed(
                    primary = Color(0xFFFFA25C),
                    onPrimary = Color(0xFF3B1A00),
                    secondary = Color(0xFFFF8AC4),
                    background = Color(0xFF1A0B0A),
                    surface = Color(0xFF2A1614),
                    onSurface = Color(0xFFFAEAE2),
                    onSurfaceVariant = Color(0xFFD6B8AB),
                    outline = Color(0xFF4A2C27),
                    error = Color(0xFFFFB4AB),
                ),
        )

    private val Night =
        PalettePair(
            id = "time-of-day",
            light =
                PaletteSeed(
                    primary = Color(0xFF3F51A8),
                    onPrimary = Color(0xFFFFFFFF),
                    secondary = Color(0xFF5C6890),
                    background = Color(0xFFF6F7FC),
                    surface = Color(0xFFE8EBF6),
                    onSurface = Color(0xFF0D1020),
                    onSurfaceVariant = Color(0xFF4B5268),
                    outline = Color(0xFFCACFE2),
                    error = Color(0xFFBA1A1A),
                ),
            dark =
                PaletteSeed(
                    primary = Color(0xFFA9B8FF),
                    onPrimary = Color(0xFF101A4F),
                    secondary = Color(0xFFC6CDE8),
                    background = Color(0xFF05070F),
                    surface = Color(0xFF0F1424),
                    onSurface = Color(0xFFECEFFA),
                    onSurfaceVariant = Color(0xFFAEB5CE),
                    outline = Color(0xFF252B44),
                    error = Color(0xFFFFB4AB),
                ),
        )
}
