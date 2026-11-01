/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaveLoadingTest {
    @Test
    fun barsStayWithinTheBox() {
        for (step in 0..40) {
            val phase = step / 40f
            for (i in 0 until WaveLoading.BAR_COUNT) {
                val h = WaveLoading.barHeight(i, phase)
                assertThat(h).isAtLeast(WaveLoading.MIN_HEIGHT - 1e-5f)
                assertThat(h).isAtMost(WaveLoading.MAX_HEIGHT + 1e-5f)
            }
        }
    }

    @Test
    fun barsAreOutOfStepSoTheWaveTravels() {
        val heights = (0 until WaveLoading.BAR_COUNT).map { WaveLoading.barHeight(it, 0.1f) }
        assertThat(heights.toSet().size).isGreaterThan(1)
        // One bar later in the cycle is the next bar's shape.
        val shift = 1f / WaveLoading.BAR_COUNT
        assertThat(WaveLoading.barHeight(1, 0.1f + shift)).isWithin(1e-5f).of(WaveLoading.barHeight(0, 0.1f))
    }

    @Test
    fun stillPhaseIsAVisibleWave() {
        val still = (0 until WaveLoading.BAR_COUNT).map { WaveLoading.barHeight(it, WaveLoading.STILL_PHASE) }
        assertThat(still.max() - still.min()).isGreaterThan(0.3f)
    }
}
