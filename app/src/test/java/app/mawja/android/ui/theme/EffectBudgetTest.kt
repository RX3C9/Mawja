/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EffectBudgetTest {
    private fun level(
        premium: Boolean = true,
        lowRam: Boolean = false,
        saver: Boolean = false,
        reduceMotion: Boolean = false,
        highContrast: Boolean = false,
    ) = EffectBudget.level(premium, lowRam, saver, reduceMotion, highContrast)

    @Test
    fun `a premium theme on a healthy device gets the full budget`() {
        assertThat(level()).isEqualTo(EffectLevel.FULL)
    }

    @Test
    fun `free themes and high contrast never draw depth`() {
        assertThat(level(premium = false)).isEqualTo(EffectLevel.OFF)
        assertThat(level(highContrast = true)).isEqualTo(EffectLevel.OFF)
        assertThat(level(premium = false, lowRam = true, saver = true, reduceMotion = true)).isEqualTo(EffectLevel.OFF)
    }

    @Test
    fun `each constraint alone steps the budget down to reduced`() {
        assertThat(level(lowRam = true)).isEqualTo(EffectLevel.REDUCED)
        assertThat(level(saver = true)).isEqualTo(EffectLevel.REDUCED)
        assertThat(level(reduceMotion = true)).isEqualTo(EffectLevel.REDUCED)
        assertThat(level(saver = true, reduceMotion = true)).isEqualTo(EffectLevel.REDUCED)
    }

    @Test
    fun `a low ram device on battery saver turns depth off`() {
        assertThat(level(lowRam = true, saver = true)).isEqualTo(EffectLevel.OFF)
    }

    @Test
    fun `blur needs the full budget and Android 12`() {
        assertThat(EffectBudget.allowsBlur(EffectLevel.FULL, 31)).isTrue()
        assertThat(EffectBudget.allowsBlur(EffectLevel.FULL, 34)).isTrue()
        assertThat(EffectBudget.allowsBlur(EffectLevel.FULL, 30)).isFalse()
        assertThat(EffectBudget.allowsBlur(EffectLevel.FULL, 26)).isFalse()
        assertThat(EffectBudget.allowsBlur(EffectLevel.REDUCED, 34)).isFalse()
        assertThat(EffectBudget.allowsBlur(EffectLevel.OFF, 34)).isFalse()
    }

    @Test
    fun `glass is translucent only at full budget and nearly solid without blur`() {
        val depth = MawjaDepth.Default
        assertThat(depth.containerAlpha(EffectLevel.FULL, 34)).isEqualTo(depth.glassAlpha)
        assertThat(depth.containerAlpha(EffectLevel.FULL, 28)).isEqualTo(depth.scrimAlpha)
        assertThat(depth.containerAlpha(EffectLevel.REDUCED, 34)).isEqualTo(1f)
        assertThat(depth.containerAlpha(EffectLevel.OFF, 34)).isEqualTo(1f)
        assertThat(depth.scrimAlpha).isGreaterThan(depth.glassAlpha)
        assertThat(depth.glassColor(Color.White, EffectLevel.OFF, 34)).isEqualTo(Color.White)
        assertThat(depth.glassColor(Color.White, EffectLevel.FULL, 34).alpha).isWithin(0.01f).of(depth.glassAlpha)
    }

    @Test
    fun `elevation tokens are ordered and step down with the budget`() {
        val depth = MawjaDepth.Default
        assertThat(depth.level1.value).isLessThan(depth.level2.value)
        assertThat(depth.level2.value).isLessThan(depth.level3.value)
        assertThat(depth.chromeElevation(EffectLevel.FULL, depth.level3).value).isEqualTo(depth.level3.value)
        assertThat(depth.chromeElevation(EffectLevel.REDUCED, depth.level3).value).isEqualTo(depth.level1.value)
        assertThat(depth.chromeElevation(EffectLevel.OFF, depth.level3).value).isEqualTo(0f)
        assertThat(depth.edgeGlowAlpha).isAtMost(0.25f)
    }
}
