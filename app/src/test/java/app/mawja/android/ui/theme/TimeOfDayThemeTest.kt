/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Every case passes a fixed minute of the day, so nothing here depends on the device clock. */
class TimeOfDayThemeTest {
    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun at(
        hour: Int,
        minute: Int = 0,
    ): Int = hour * 60 + minute

    @Test
    fun `phases start exactly on their boundaries`() {
        assertThat(TimeOfDayTheme.phaseAt(at(4, 59))).isEqualTo(DayPhase.NIGHT)
        assertThat(TimeOfDayTheme.phaseAt(at(5))).isEqualTo(DayPhase.DAWN)
        assertThat(TimeOfDayTheme.phaseAt(at(7, 59))).isEqualTo(DayPhase.DAWN)
        assertThat(TimeOfDayTheme.phaseAt(at(8))).isEqualTo(DayPhase.NOON)
        assertThat(TimeOfDayTheme.phaseAt(at(16, 29))).isEqualTo(DayPhase.NOON)
        assertThat(TimeOfDayTheme.phaseAt(at(16, 30))).isEqualTo(DayPhase.SUNSET)
        assertThat(TimeOfDayTheme.phaseAt(at(18, 59))).isEqualTo(DayPhase.SUNSET)
        assertThat(TimeOfDayTheme.phaseAt(at(19))).isEqualTo(DayPhase.NIGHT)
        assertThat(TimeOfDayTheme.phaseAt(at(23, 59))).isEqualTo(DayPhase.NIGHT)
    }

    @Test
    fun `night wraps across midnight`() {
        assertThat(TimeOfDayTheme.phaseAt(0)).isEqualTo(DayPhase.NIGHT)
        assertThat(TimeOfDayTheme.minutesIntoPhase(0)).isEqualTo(5 * 60)
        assertThat(TimeOfDayTheme.minutesIntoPhase(at(23, 59))).isEqualTo(4 * 60 + 59)
        assertThat(TimeOfDayTheme.normalize(TimeOfDayTheme.MINUTES_PER_DAY)).isEqualTo(0)
        assertThat(TimeOfDayTheme.normalize(-1)).isEqualTo(TimeOfDayTheme.MINUTES_PER_DAY - 1)
        assertThat(TimeOfDayTheme.phaseAt(-1)).isEqualTo(DayPhase.NIGHT)
        assertThat(TimeOfDayTheme.previous(DayPhase.DAWN)).isEqualTo(DayPhase.NIGHT)
        assertThat(TimeOfDayTheme.previous(DayPhase.NOON)).isEqualTo(DayPhase.DAWN)
    }

    @Test
    fun `blend runs from 0 to 1 over the transition and stays in range`() {
        assertThat(TimeOfDayTheme.blendAt(at(8))).isEqualTo(0f)
        assertThat(TimeOfDayTheme.blendAt(at(8) + TimeOfDayTheme.TRANSITION_MINUTES)).isEqualTo(1f)
        assertThat(TimeOfDayTheme.blendAt(at(12))).isEqualTo(1f)
        val half = TimeOfDayTheme.blendAt(at(8) + TimeOfDayTheme.TRANSITION_MINUTES / 2)
        assertThat(half).isGreaterThan(0f)
        assertThat(half).isLessThan(1f)
        for (m in 0 until TimeOfDayTheme.MINUTES_PER_DAY) {
            val t = TimeOfDayTheme.blendAt(m)
            assertThat(t).isAtLeast(0f)
            assertThat(t).isAtMost(1f)
        }
    }

    @Test
    fun `a phase opens on the previous colours and settles on its own`() {
        val atStart = TimeOfDayTheme.pairAt(at(8))
        assertThat(atStart.dark.primary).isEqualTo(TimeOfDayTheme.pairFor(DayPhase.DAWN).dark.primary)
        val settled = TimeOfDayTheme.pairAt(at(12))
        assertThat(settled).isEqualTo(TimeOfDayTheme.pairFor(DayPhase.NOON))
        assertThat(TimeOfDayTheme.pairAt(at(2)).id).isEqualTo("time-of-day")
    }

    @Test
    fun `the clock is read on a five minute grid`() {
        assertThat(TimeOfDayTheme.quantize(at(10, 7))).isEqualTo(at(10, 5))
        assertThat(TimeOfDayTheme.quantize(at(10, 5))).isEqualTo(at(10, 5))
        assertThat(TimeOfDayTheme.minutesToNextStep(at(10, 7))).isEqualTo(3)
        assertThat(TimeOfDayTheme.minutesToNextStep(at(10, 5))).isEqualTo(5)
    }

    @Test
    fun `every minute of the day meets contrast minimums in both styles`() {
        for (m in 0 until TimeOfDayTheme.MINUTES_PER_DAY step TimeOfDayTheme.STEP_MINUTES) {
            val pair = TimeOfDayTheme.pairAt(m)
            listOf(ThemeVariant.LIGHT, ThemeVariant.DARK).forEach { variant ->
                val c = pair.colorsFor(variant)
                assertThat(contrast(c.primary, c.background)).isAtLeast(3f)
                assertThat(contrast(c.onPrimary, c.primary)).isAtLeast(4.5f)
                assertThat(contrast(c.onSurface, c.surface)).isAtLeast(4.5f)
                assertThat(contrast(c.onSurfaceVariant, c.surface)).isAtLeast(4.5f)
            }
        }
    }

    @Test
    fun `the theme resolves through the palette lookup`() {
        assertThat(MawjaPalettes.forMode(ThemeMode.TIME_OF_DAY).id).isEqualTo("time-of-day")
        assertThat(PremiumThemes.isPremium(ThemeMode.TIME_OF_DAY)).isTrue()
        assertThat(ThemeMode.MIDNIGHT_OLED.defaultVariant()).isEqualTo(ThemeVariant.AMOLED)
    }
}
