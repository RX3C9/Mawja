/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartNetworkTest {
    private val wifi = NetworkSnapshot(online = true, wifi = true, ethernet = false, metered = false, downKbps = 50_000, dataSaver = false)
    private val cell = wifi.copy(wifi = false, metered = true, downKbps = 20_000)

    @Test
    fun `classifies the common connections`() {
        assertEquals(NetworkProfile.FAST_UNMETERED, SmartNetwork.classify(wifi))
        assertEquals(NetworkProfile.FAST_UNMETERED, SmartNetwork.classify(wifi.copy(wifi = false, ethernet = true)))
        assertEquals(NetworkProfile.METERED_WIFI, SmartNetwork.classify(wifi.copy(metered = true)))
        assertEquals(NetworkProfile.METERED_WIFI, SmartNetwork.classify(wifi.copy(dataSaver = true)))
        assertEquals(NetworkProfile.SLOW_WIFI, SmartNetwork.classify(wifi.copy(downKbps = 800)))
        assertEquals(NetworkProfile.CELLULAR, SmartNetwork.classify(cell))
        assertEquals(NetworkProfile.SLOW_CELLULAR, SmartNetwork.classify(cell.copy(downKbps = 300)))
        assertEquals(NetworkProfile.OFFLINE, SmartNetwork.classify(wifi.copy(online = false)))
    }

    @Test
    fun `an unknown bandwidth estimate is not slow`() {
        assertEquals(NetworkProfile.FAST_UNMETERED, SmartNetwork.classify(wifi.copy(downKbps = 0)))
        assertEquals(NetworkProfile.CELLULAR, SmartNetwork.classify(cell.copy(downKbps = 0)))
    }

    @Test
    fun `smart off keeps the original wifi rule`() {
        assertTrue(SmartNetwork.treatAsWifi(wifi.copy(metered = true, downKbps = 200), smart = false))
        assertFalse(SmartNetwork.treatAsWifi(wifi.copy(wifi = false, ethernet = true), smart = false))
        assertFalse(SmartNetwork.treatAsWifi(cell, smart = false))
    }

    @Test
    fun `smart on wants a fast unmetered link`() {
        assertTrue(SmartNetwork.treatAsWifi(wifi, smart = true))
        assertTrue(SmartNetwork.treatAsWifi(wifi.copy(wifi = false, ethernet = true), smart = true))
        assertFalse(SmartNetwork.treatAsWifi(wifi.copy(metered = true), smart = true))
        assertFalse(SmartNetwork.treatAsWifi(wifi.copy(downKbps = 900), smart = true))
        assertFalse(SmartNetwork.treatAsWifi(wifi.copy(dataSaver = true), smart = true))
        assertFalse(SmartNetwork.treatAsWifi(cell, smart = true))
        assertFalse(SmartNetwork.treatAsWifi(wifi.copy(online = false), smart = true))
    }
}
