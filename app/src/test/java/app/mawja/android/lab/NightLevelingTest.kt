/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightLevelingTest {
    @Test fun offDuringTheDay() = assertEquals(0f, NightLeveling.strength(12 * 60, 0), 0f)

    @Test fun offAtTheVeryStartOfTheWindow() = assertEquals(0f, NightLeveling.strength(0, 0), 0f)

    @Test fun easesInInSteps() {
        assertEquals(0.5f, NightLeveling.strength(8, 0), 0f)
        assertEquals(0.5f, NightLeveling.strength(15, 0), 0f)
        assertEquals(0.75f, NightLeveling.strength(16, 0), 0f)
    }

    @Test fun fullOnceTheRampIsOver() = assertEquals(1f, NightLeveling.strength(2 * 60, 0), 0f)

    @Test fun followsAnEarlierStartHour() = assertEquals(1f, NightLeveling.strength(22 * 60 + 45, 22), 0f)

    @Test fun noSettingsWhenOff() {
        assertNull(NightLeveling.settingsFor(0f))
        assertNull(NightLeveling.settingsFor(Float.NaN))
    }

    @Test fun deepestAtFullStrength() {
        val s = NightLeveling.settingsFor(1f)!!
        assertEquals(-20f, s.thresholdDb, 0.001f)
        assertEquals(10f, s.ratio, 0.001f)
        assertEquals(8f, s.postGainDb, 0.001f)
        assertEquals(-12f, NightLeveling.ceilingDb(s), 0.001f)
    }

    @Test fun halfwayAtHalfStrength() {
        val s = NightLeveling.settingsFor(0.5f)!!
        assertEquals(-15f, s.thresholdDb, 0.001f)
        assertEquals(7f, s.ratio, 0.001f)
        assertEquals(5f, s.postGainDb, 0.001f)
    }

    @Test fun neverPastFullStrength() = assertEquals(NightLeveling.settingsFor(1f), NightLeveling.settingsFor(3f))

    @Test fun leveledAudioAlwaysStaysBelowFullScale() {
        for (step in 1..NightLeveling.STEPS) {
            val s = NightLeveling.settingsFor(step.toFloat() / NightLeveling.STEPS)!!
            assertTrue(NightLeveling.ceilingDb(s) < 0f)
        }
    }
}
