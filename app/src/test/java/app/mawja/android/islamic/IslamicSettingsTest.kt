/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.islamic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslamicSettingsTest {
    @Test
    fun offByDefaultWithNoPlace() {
        val s = IslamicSettings()
        assertFalse(s.enabled)
        assertEquals(AdhanMode.OFF, s.adhan)
        assertNull(s.place())
        assertEquals(CalcMethod.EGYPTIAN, s.method("EG"))
        assertEquals(CalcMethod.UMM_AL_QURA, s.method("SA"))
        assertEquals(CalcMethod.MWL, s.copy(methodKey = "mwl").method("EG"))
    }

    @Test
    fun placeResolution() {
        assertEquals(30.0444, IslamicSettings(cityId = "cairo").place()!!.lat, 1e-9)
        assertNull(IslamicSettings(cityId = "atlantis").place())
        val manual = IslamicSettings(locationSource = LocationSource.MANUAL, lat = 10.0, lng = 20.0).place()!!
        assertNull(manual.city)
        assertNull(IslamicSettings(locationSource = LocationSource.MANUAL, lat = 95.0, lng = 20.0).place())
        val device = IslamicSettings(locationSource = LocationSource.DEVICE, lat = 30.06, lng = 31.25).place()!!
        assertEquals("cairo", device.city!!.id)
    }

    @Test
    fun parsesTypedCoordinates() {
        assertEquals(30.0444, IslamicSettings.parseCoordinate(" 30.0444 ")!!, 1e-9)
        assertEquals(30.5, IslamicSettings.parseCoordinate("٣٠٫٥")!!, 1e-9)
        assertEquals(-6.2, IslamicSettings.parseCoordinate("-6,2")!!, 1e-9)
        assertNull(IslamicSettings.parseCoordinate("north"))
        assertNull(IslamicSettings.parseCoordinate("NaN"))
    }

    @Test
    fun offsetsCodecDropsJunk() {
        val offsets = mapOf(Prayer.FAJR to 2, Prayer.MAGHRIB to -3, Prayer.ISHA to 0)
        assertEquals(mapOf(Prayer.FAJR to 2, Prayer.MAGHRIB to -3), IslamicSettings.decodeOffsets(IslamicSettings.encodeOffsets(offsets)))
        assertEquals(mapOf(Prayer.ASR to 5), IslamicSettings.decodeOffsets("asr:5,witr:3,isha:99,dhuhr:x,,"))
        assertTrue(IslamicSettings.decodeOffsets(null).isEmpty())
    }

    @Test
    fun citySearchInBothLanguages() {
        assertEquals("cairo", IslamicCities.search("القاهره").first().id)
        assertEquals("cairo", IslamicCities.search("CAI").first().id)
        assertTrue(IslamicCities.search("اسكندرية").any { it.id == "alexandria" })
        assertEquals(IslamicCities.all.size, IslamicCities.search("  ").size)
        assertEquals(IslamicCities.all.size, IslamicCities.all.map { it.id }.toSet().size)
    }
}
