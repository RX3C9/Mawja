/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.components.motion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MicroMotionTest {
    @Test
    fun pressSinksOnlyWhilePressedAndMotionAllowed() {
        assertThat(MicroMotion.pressScale(pressed = true, reduceMotion = false)).isEqualTo(MicroMotion.PRESS_SCALE)
        assertThat(MicroMotion.pressScale(pressed = false, reduceMotion = false)).isEqualTo(1f)
        assertThat(MicroMotion.pressScale(pressed = true, reduceMotion = true)).isEqualTo(1f)
    }

    @Test
    fun playerOpensFromNinetyFourPercent() {
        assertThat(MicroMotion.playerOpenScale(0f, reduceMotion = false)).isWithin(1e-6f).of(0.94f)
        assertThat(MicroMotion.playerOpenScale(0.5f, reduceMotion = false)).isWithin(1e-6f).of(0.97f)
        assertThat(MicroMotion.playerOpenScale(1f, reduceMotion = false)).isWithin(1e-6f).of(1f)
        // Out-of-range fractions clamp.
        assertThat(MicroMotion.playerOpenScale(-1f, reduceMotion = false)).isWithin(1e-6f).of(0.94f)
        assertThat(MicroMotion.playerOpenScale(3f, reduceMotion = false)).isWithin(1e-6f).of(1f)
    }

    @Test
    fun playerOpenZoomIsOffUnderReduceMotion() {
        listOf(0f, 0.3f, 1f).forEach { assertThat(MicroMotion.playerOpenScale(it, reduceMotion = true)).isEqualTo(1f) }
    }

    @Test
    fun staggerCascadesTheFirstItemsOnly() {
        for (i in 0 until StaggerTiming.MAX_ITEMS) {
            assertThat(StaggerTiming.delayMillis(i, firstLoadPlaying = true, reduceMotion = false))
                .isEqualTo(i * StaggerTiming.STEP_MILLIS)
        }
        assertThat(StaggerTiming.delayMillis(StaggerTiming.MAX_ITEMS, firstLoadPlaying = true, reduceMotion = false)).isNull()
        assertThat(StaggerTiming.delayMillis(-1, firstLoadPlaying = true, reduceMotion = false)).isNull()
    }

    @Test
    fun staggerStopsAfterFirstLoadAndUnderReduceMotion() {
        assertThat(StaggerTiming.delayMillis(0, firstLoadPlaying = false, reduceMotion = false)).isNull()
        assertThat(StaggerTiming.delayMillis(2, firstLoadPlaying = true, reduceMotion = true)).isNull()
    }

    @Test
    fun cascadeEndsAfterTheLastItemFinishes() {
        val lastDelay = (StaggerTiming.MAX_ITEMS - 1) * StaggerTiming.STEP_MILLIS
        assertThat(StaggerTiming.totalMillis).isEqualTo(lastDelay + StaggerTiming.DURATION_MILLIS)
        assertThat(StaggerTiming.totalMillis).isLessThan(1000)
    }
}
