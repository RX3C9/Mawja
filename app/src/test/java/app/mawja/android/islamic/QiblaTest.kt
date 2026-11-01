/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QiblaTest {
    @Test
    fun bearingsMatchPublishedValues() {
        // Published qibla directions: Cairo ~136°, Jakarta ~295°, London ~119°, New York ~58.5°.
        assertEquals(136.14, Qibla.bearing(30.0444, 31.2357), 0.5)
        assertEquals(295.15, Qibla.bearing(-6.2088, 106.8456), 0.5)
        assertEquals(118.99, Qibla.bearing(51.5074, -0.1278), 0.5)
        assertEquals(58.48, Qibla.bearing(40.7128, -74.0060), 0.5)
    }

    @Test
    fun distanceToMakkah() {
        assertEquals(1285.0, Qibla.distanceKm(30.0444, 31.2357), 15.0)
        assertEquals(0.0, Qibla.distanceKm(Qibla.KAABA_LAT, Qibla.KAABA_LNG), 0.01)
    }

    @Test
    fun relativeTurnWrapsAroundNorth() {
        assertEquals(10.0, Qibla.relative(5.0, 355.0), 1e-9)
        assertEquals(-10.0, Qibla.relative(355.0, 5.0), 1e-9)
        assertEquals(-44.0, Qibla.relative(136.0, 180.0), 1e-9)
        assertTrue(Qibla.isAligned(136.0, 133.0))
        assertFalse(Qibla.isAligned(136.0, 120.0))
    }

    @Test
    fun smoothingAveragesAcrossNorth() {
        assertEquals(0.0, Qibla.smooth(358.0, 2.0, 0.5), 1e-9)
        assertEquals(90.0, Qibla.smooth(null, 90.0, 0.2), 1e-9)
        assertEquals(20.0, Qibla.smooth(10.0, 20.0, 1.0), 1e-9)
    }

    @Test
    fun compassPoints() {
        assertEquals(3, Qibla.compassPoint(Qibla.bearing(30.0444, 31.2357))) // Cairo: south-east
        assertEquals(7, Qibla.compassPoint(Qibla.bearing(-6.2088, 106.8456))) // Jakarta: north-west
        assertEquals(0, Qibla.compassPoint(359.0))
    }
}
