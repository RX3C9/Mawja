/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSettingsTest {
    private val doc =
        """
        {"name":"projects/p/databases/(default)/documents/config/app","fields":{
          "killSwitch":{"booleanValue":false},
          "maintenance":{"booleanValue":false},
          "maintenanceMessage":{"stringValue":"Back soon"},
          "maintenanceMessageAr":{"stringValue":"نعود قريبا"},
          "minVersion":{"integerValue":"10"},
          "latestVersion":{"doubleValue":20.0},
          "updateUrl":{"stringValue":"https://mawja.app/download"},
          "checkoutUrl":{"stringValue":"http://insecure.example"},
          "features":{"mapValue":{"fields":{"downloads":{"booleanValue":false},"sync":{"booleanValue":true},"bad key":{"booleanValue":false},"lastfm":{"stringValue":"no"}}}},
          "tierGates":{"mapValue":{"fields":{"VISUALIZER":{"stringValue":"plus"},"UI_SOUNDS":{"stringValue":"diamond"}}}},
          "unknown":{"nullValue":null}
        },"createTime":"2026-10-01T00:00:00Z"}
        """.trimIndent()

    @Test
    fun parsesFirestoreDocument() {
        val s = RemoteSettings.fromFirestore(doc)
        assertEquals("Back soon", s.maintenanceMessage)
        assertEquals("نعود قريبا", s.maintenanceMessageAr)
        assertEquals(10L, s.minVersion)
        assertEquals(20L, s.latestVersion)
        assertEquals(mapOf("downloads" to false, "sync" to true), s.features)
        assertEquals("plus", s.tierGates["VISUALIZER"])
    }

    @Test
    fun garbageAndEmptyAreDefault() {
        assertEquals(RemoteSettings.DEFAULT, RemoteSettings.fromFirestore("not json"))
        assertEquals(RemoteSettings.DEFAULT, RemoteSettings.fromFirestore("{}"))
        assertEquals(RemoteSettings.DEFAULT, RemoteSettings.fromFirestore("""{"fields":{"killSwitch":{"stringValue":"yes"}}}"""))
    }

    @Test
    fun featuresDefaultToEnabled() {
        val s = RemoteSettings.fromFirestore(doc)
        assertFalse(s.isEnabled("downloads"))
        assertTrue(s.isEnabled("sync"))
        assertTrue(s.isEnabled("lastfm"))
        assertTrue(s.isEnabled("neverHeardOf"))
        assertTrue(RemoteSettings.DEFAULT.isEnabled("downloads"))
    }

    @Test
    fun gateOrder() {
        val s = RemoteSettings.fromFirestore(doc)
        assertEquals(RemoteGate.Open, s.gate(15))
        assertEquals(RemoteGate.ForceUpdate("https://mawja.app/download"), s.gate(9))
        assertEquals(RemoteGate.Open, s.gate(0))
        assertEquals(RemoteGate.Maintenance("نعود قريبا", "Back soon"), s.copy(maintenance = true).gate(9))
        assertEquals(RemoteGate.Killed, s.copy(maintenance = true, killSwitch = true).gate(15))
        assertEquals(RemoteGate.ForceUpdate(""), s.copy(updateUrl = "javascript:alert(1)").gate(1))
        assertEquals(RemoteGate.Open, RemoteSettings.DEFAULT.gate(1))
    }

    @Test
    fun maintenanceMessageLocaleFallback() {
        val m = RemoteGate.Maintenance("عربي", "English")
        assertEquals("عربي", m.message("ar"))
        assertEquals("عربي", m.message("ar-EG"))
        assertEquals("English", m.message("en"))
        assertEquals("English", RemoteGate.Maintenance("", "English").message("ar"))
        assertEquals("عربي", RemoteGate.Maintenance("عربي", "").message("en"))
    }

    @Test
    fun softUpdate() {
        val s = RemoteSettings.fromFirestore(doc)
        assertTrue(s.softUpdate(15))
        assertFalse(s.softUpdate(20))
        assertFalse(s.softUpdate(9))
        assertFalse(s.softUpdate(0))
        assertFalse(s.copy(maintenance = true).softUpdate(15))
    }

    @Test
    fun checkoutUrlMustBeHttps() {
        assertNull(RemoteSettings.fromFirestore(doc).checkoutUrlOrNull)
        assertEquals("https://pay.example/x", RemoteSettings(checkoutUrl = "https://pay.example/x").checkoutUrlOrNull)
        assertNull(RemoteSettings.DEFAULT.checkoutUrlOrNull)
    }

    @Test
    fun tierGates() {
        val s = RemoteSettings.fromFirestore(doc)
        assertEquals("plus", s.requiredTier("VISUALIZER"))
        assertNull(s.requiredTier("UI_SOUNDS"))
        assertNull(s.tierAllows("PREMIUM_THEMES", "free"))
        assertEquals(false, s.tierAllows("VISUALIZER", "free"))
        assertEquals(true, s.tierAllows("VISUALIZER", "plus"))
        assertEquals(true, s.tierAllows("VISUALIZER", "family"))
        assertTrue(TierRank.meets("lifetime", "pro"))
        assertTrue(TierRank.meets("pro", "lifetime"))
        assertFalse(TierRank.meets("pro", "family"))
        assertFalse(TierRank.meets(null, "plus"))
        assertFalse(TierRank.meets("bogus", "plus"))
        assertTrue(TierRank.meets("FAMILY", "pro"))
    }

    @Test
    fun cacheRoundTrip() {
        val s = RemoteSettings.fromFirestore(doc).copy(killSwitch = true, maintenanceMessage = "quote \" and \n newline")
        assertEquals(s, RemoteSettings.decode(s.encode()))
        assertEquals(RemoteSettings.DEFAULT, RemoteSettings.decode(RemoteSettings.DEFAULT.encode()))
    }

    @Test
    fun corruptCacheIsNull() {
        assertNull(RemoteSettings.decode(null))
        assertNull(RemoteSettings.decode(""))
        assertNull(RemoteSettings.decode("{broken"))
        assertNull(RemoteSettings.decode("[]"))
        assertNull(RemoteSettings.decode("""{"v":99,"killSwitch":true}"""))
    }
}
