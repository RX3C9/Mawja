/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigTest {
    @Test
    fun readsFirestoreDataLeniently() {
        val cfg =
            RemoteConfig.from(
                mapOf(
                    "killSwitch" to true,
                    "minVersion" to 19L,
                    "latestVersion" to 20.0,
                    "maintenanceMessage" to "Back soon",
                    "features" to mapOf("sync" to true, "bad" to "yes", 3 to true),
                    "tierGates" to mapOf("PREMIUM_THEMES" to "plus", "X" to 1),
                    "legacy" to "kept",
                ),
            )
        assertTrue(cfg.killSwitch)
        assertEquals(19L, cfg.minVersion)
        assertEquals(20L, cfg.latestVersion)
        assertEquals(mapOf("sync" to true), cfg.features)
        assertEquals(mapOf("PREMIUM_THEMES" to "plus"), cfg.tierGates)
        assertEquals(RemoteConfig(), RemoteConfig.from(null))
    }

    @Test
    fun writesOnlyWhatChanged() {
        val old = RemoteConfig(minVersion = 10, features = mapOf("sync" to true))
        assertEquals(emptyMap<String, Any>(), old.changesFrom(old))
        val nu = old.copy(minVersion = 12, maintenance = true, features = mapOf("sync" to false, "lastfm" to true))
        val changes = nu.changesFrom(old)
        assertEquals(setOf("minVersion", "maintenance", "features"), changes.keys)
        assertEquals(12L, changes["minVersion"])
        assertEquals(mapOf("lastfm" to true, "sync" to false), changes["features"])
    }

    @Test
    fun validates() {
        assertTrue(RemoteConfig().problems().isEmpty())
        assertTrue("latest_below_min" in RemoteConfig(minVersion = 20, latestVersion = 19).problems())
        assertTrue(RemoteConfig(minVersion = 20, latestVersion = 0).problems().isEmpty())
        assertTrue("update_url" in RemoteConfig(updateUrl = "http://x").problems())
        assertTrue("bad_key" in RemoteConfig(features = mapOf("has space" to true)).problems())
        assertTrue("bad_tier" in RemoteConfig(tierGates = mapOf("VISUALIZER" to "gold")).problems())
        assertTrue("message_long" in RemoteConfig(maintenanceMessage = "x".repeat(501)).problems())
        assertTrue(RemoteConfig.isFlagKey("smartNotifications"))
        assertFalse(RemoteConfig.isFlagKey("1abc"))
    }

    @Test
    fun killSwitchAndMinimumVersion() {
        assertFalse(RemoteConfig().blocks(1))
        assertTrue(RemoteConfig(killSwitch = true).blocks(99))
        assertTrue(RemoteConfig(minVersion = 3).blocks(2))
        assertFalse(RemoteConfig(minVersion = 3).blocks(3))
        assertEquals(3, Component.entries.size)
        assertTrue("sync" in RemoteConfig.catalogue(Component.APP))
    }

    @Test
    fun updateFields() {
        val sha = "c".repeat(64)
        val old = RemoteConfig()
        val new = old.copy(updateReasonAr = "تصليح", updateSha256 = sha.uppercase(), requiredAfter = 5000, rolloutPercent = 10, pulledVersions = listOf(28, 27, 28))
        val ch = new.changesFrom(old)
        assertEquals(sha, ch["updateSha256"])
        assertEquals(java.util.Date(5000), ch["requiredAfter"])
        assertEquals(10L, ch["rolloutPercent"])
        assertEquals(listOf(27L, 28L), ch["pulledVersions"])
        assertTrue(new.problems().isEmpty())
        assertTrue("sha256" in RemoteConfig(updateSha256 = "xyz").problems())
        assertTrue("rollout" in RemoteConfig(rolloutPercent = 120).problems())
        assertEquals(listOf(27L, 28L), RemoteConfig.parseVersions("27, 28,x, 0"))
        val back = RemoteConfig.from(mapOf("requiredAfter" to java.util.Date(5000), "rolloutPercent" to 10L, "pulledVersions" to listOf(27L)))
        assertEquals(5000L, back.requiredAfter)
        assertEquals(10, back.rolloutPercent)
        assertEquals(100, RemoteConfig.from(emptyMap()).rolloutPercent)
    }
}
