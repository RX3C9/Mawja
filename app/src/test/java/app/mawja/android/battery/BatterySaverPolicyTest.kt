/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.battery

import app.mawja.android.data.local.VideoQuality
import app.mawja.android.ui.theme.ThemeVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatterySaverPolicyTest {
    private val auto = BatterySaverSettings(mode = BatterySaverMode.AUTO, thresholdPercent = 20)
    private val full = BatteryReading(levelPercent = 80, charging = false, powerSave = false)

    @Test
    fun `auto turns on at or below the threshold`() {
        assertFalse(BatterySaverPolicy.isActive(auto, full, remoteEnabled = true))
        assertFalse(BatterySaverPolicy.isActive(auto, full.copy(levelPercent = 21), remoteEnabled = true))
        assertTrue(BatterySaverPolicy.isActive(auto, full.copy(levelPercent = 20), remoteEnabled = true))
        assertTrue(BatterySaverPolicy.isActive(auto, full.copy(levelPercent = 5), remoteEnabled = true))
        assertEquals(BatterySaverReason.LOW_BATTERY, BatterySaverPolicy.reason(auto, full.copy(levelPercent = 12), true))
    }

    @Test
    fun `auto follows the system power-save mode at any level`() {
        val saver = full.copy(powerSave = true)
        assertTrue(BatterySaverPolicy.isActive(auto, saver, remoteEnabled = true))
        assertTrue(BatterySaverPolicy.isActive(auto, saver.copy(charging = true), remoteEnabled = true))
        assertEquals(BatterySaverReason.SYSTEM_POWER_SAVE, BatterySaverPolicy.reason(auto, saver, true))
    }

    @Test
    fun `charging keeps auto off even when low`() {
        val low = full.copy(levelPercent = 10, charging = true)
        assertFalse(BatterySaverPolicy.isActive(auto, low, remoteEnabled = true))
        assertEquals(BatterySaverReason.CHARGING, BatterySaverPolicy.reason(auto, low, true))
    }

    @Test
    fun `an unknown level never turns auto on`() {
        assertFalse(BatterySaverPolicy.isActive(auto, BatteryReading.UNKNOWN, remoteEnabled = true))
    }

    @Test
    fun `hysteresis keeps it on until the level clears the threshold by a margin`() {
        val justAbove = full.copy(levelPercent = 22)
        assertFalse(BatterySaverPolicy.isActive(auto, justAbove, remoteEnabled = true, wasActive = false))
        assertTrue(BatterySaverPolicy.isActive(auto, justAbove, remoteEnabled = true, wasActive = true))
        assertTrue(BatterySaverPolicy.isActive(auto, full.copy(levelPercent = 23), remoteEnabled = true, wasActive = true))
        assertFalse(BatterySaverPolicy.isActive(auto, full.copy(levelPercent = 24), remoteEnabled = true, wasActive = true))
    }

    @Test
    fun `off and always ignore the battery`() {
        val off = auto.copy(mode = BatterySaverMode.OFF)
        val always = auto.copy(mode = BatterySaverMode.ALWAYS)
        assertFalse(BatterySaverPolicy.isActive(off, full.copy(levelPercent = 3, powerSave = true), remoteEnabled = true))
        assertTrue(BatterySaverPolicy.isActive(always, full.copy(charging = true), remoteEnabled = true))
        assertEquals(BatterySaverReason.ALWAYS, BatterySaverPolicy.reason(always, full, true))
    }

    @Test
    fun `the remote switch turns it off whatever the mode`() {
        val always = auto.copy(mode = BatterySaverMode.ALWAYS)
        assertFalse(BatterySaverPolicy.isActive(always, full, remoteEnabled = false))
        assertFalse(BatterySaverPolicy.isActive(auto, full.copy(powerSave = true), remoteEnabled = false))
        assertEquals(BatterySaverReason.REMOTE_DISABLED, BatterySaverPolicy.reason(always, full, false))
    }

    @Test
    fun `threshold is clamped`() {
        assertEquals(5, BatterySaverPolicy.clampThreshold(0))
        assertEquals(50, BatterySaverPolicy.clampThreshold(90))
        val silly = auto.copy(thresholdPercent = 95)
        assertFalse(BatterySaverPolicy.isActive(silly, full.copy(levelPercent = 60), remoteEnabled = true))
        assertTrue(BatterySaverPolicy.isActive(silly, full.copy(levelPercent = 50), remoteEnabled = true))
    }

    @Test
    fun `quality is capped at 480p only while active`() {
        assertEquals(VideoQuality.Q_480P, BatterySaverPolicy.capQuality(VideoQuality.Q_1080P, active = true))
        assertEquals(VideoQuality.Q_480P, BatterySaverPolicy.capQuality(VideoQuality.AUTO, active = true))
        assertEquals(VideoQuality.Q_360P, BatterySaverPolicy.capQuality(VideoQuality.Q_360P, active = true))
        assertEquals(VideoQuality.Q_1080P, BatterySaverPolicy.capQuality(VideoQuality.Q_1080P, active = false))
        assertEquals(VideoQuality.AUTO, BatterySaverPolicy.capQuality(VideoQuality.AUTO, active = false))
        assertEquals(480, BatterySaverPolicy.capHeight(1080, active = true))
        assertEquals(480, BatterySaverPolicy.capHeight(0, active = true))
        assertEquals(240, BatterySaverPolicy.capHeight(240, active = true))
        assertEquals(1080, BatterySaverPolicy.capHeight(1080, active = false))
    }

    @Test
    fun `prefetch, jobs, motion and theme follow the active state`() {
        assertTrue(BatterySaverPolicy.allowPrefetch(active = false))
        assertFalse(BatterySaverPolicy.allowPrefetch(active = true))
        assertFalse(BatterySaverPolicy.allowNonEssentialJob(active = true))
        assertTrue(BatterySaverPolicy.reduceMotion(auto, active = true))
        assertFalse(BatterySaverPolicy.reduceMotion(auto.copy(reduceMotion = false), active = true))
        assertFalse(BatterySaverPolicy.reduceMotion(auto, active = false))
        assertEquals(ThemeVariant.AMOLED, BatterySaverPolicy.themeVariant(ThemeVariant.DARK, auto, active = true))
        assertEquals(ThemeVariant.AMOLED, BatterySaverPolicy.themeVariant(ThemeVariant.LIGHT, auto, active = true))
        assertEquals(ThemeVariant.DARK, BatterySaverPolicy.themeVariant(ThemeVariant.DARK, auto.copy(blackTheme = false), active = true))
        assertEquals(ThemeVariant.LIGHT, BatterySaverPolicy.themeVariant(ThemeVariant.LIGHT, auto, active = false))
    }

    @Test
    fun `mode keys round-trip and default to auto`() {
        BatterySaverMode.entries.forEach { assertEquals(it, BatterySaverMode.fromKey(it.name)) }
        assertEquals(BatterySaverMode.AUTO, BatterySaverMode.fromKey(null))
        assertEquals(BatterySaverMode.AUTO, BatterySaverMode.fromKey("bogus"))
    }
}
