/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightVolumeTest {
    @Test fun fullVolumeInTheDay() {
        for (h in 6..21) assertEquals(1f, NightVolume.factor(h), 0f)
    }

    @Test fun deepNightIsQuietest() {
        for (h in listOf(23, 0, 1, 2, 3, 4)) assertEquals(0.45f, NightVolume.factor(h), 0f)
    }

    @Test fun duskAndDawnAreInBetween() {
        assertEquals(0.7f, NightVolume.factor(22), 0f)
        assertEquals(0.7f, NightVolume.factor(5), 0f)
    }

    @Test fun neverLouderThanTheDay() {
        for (h in 0..23) assertTrue(NightVolume.factor(h) <= 1f && NightVolume.factor(h) > 0f)
    }
}
