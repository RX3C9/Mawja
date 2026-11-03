/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.remote

import app.mawja.android.remote.UpdateChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mawja 2.0 (P7) update levels, staged rollout, pulled builds, cache round trip and APK checks. */
class UpdatePolicyTest {
    private val now = 1_800_000_000_000L
    private val sha = "ab".repeat(32)

    @Test
    fun levels() {
        val s = RemoteSettings(minVersion = 20, latestVersion = 30)
        assertEquals(UpdateLevel.FORCED, s.update(19, 0, now))
        assertEquals(UpdateLevel.SUGGESTED, s.update(25, 0, now))
        assertEquals(UpdateLevel.NONE, s.update(30, 0, now))
        assertEquals(UpdateLevel.NONE, s.update(0, 0, now))
        val soon = s.copy(requiredAfter = now + 1000)
        assertEquals(UpdateLevel.REQUIRED_SOON, soon.update(25, 0, now))
        assertEquals(UpdateLevel.FORCED, soon.update(25, 0, now + 1000))
        assertTrue(soon.softUpdate(25, 0, now))
        assertFalse(soon.softUpdate(25, 0, now + 1000))
        assertTrue(soon.gate(25, 0, now + 1000) is RemoteGate.ForceUpdate)
    }

    @Test
    fun stagedRolloutAndPulledBuilds() {
        val s = RemoteSettings(latestVersion = 30, rolloutPercent = 10, requiredAfter = now - 1)
        assertEquals(UpdateLevel.FORCED, s.update(25, 9, now))
        assertEquals(UpdateLevel.NONE, s.update(25, 10, now))
        assertFalse(s.copy(rolloutPercent = 0).inRollout(0))
        assertTrue(s.copy(rolloutPercent = 100).inRollout(99))
        val pulled = RemoteSettings(latestVersion = 30, rolloutPercent = 0, pulledVersions = listOf(27))
        assertEquals(UpdateLevel.FORCED, pulled.update(27, 50, now))
        assertEquals(UpdateLevel.NONE, pulled.update(28, 50, now))
        val b = RemoteSettings.bucketOf("device-1")
        assertEquals(b, RemoteSettings.bucketOf("device-1"))
        assertTrue(b in 0..99)
    }

    @Test
    fun forceUpdateCarriesReasonAndHash() {
        val s = RemoteSettings(minVersion = 30, updateUrl = "https://x.example/m.apk", updateReason = "Fix", updateReasonAr = "تصليح", updateSha256 = sha.uppercase())
        val g = s.gate(20, 0, now) as RemoteGate.ForceUpdate
        assertEquals("تصليح", g.reason("ar"))
        assertEquals("Fix", g.reason("en"))
        assertEquals(sha, g.sha256)
        assertEquals("", s.copy(updateSha256 = "nope").sha256OrEmpty)
        assertEquals("Fix", s.copy(updateReasonAr = "").updateReason("ar"))
    }

    @Test
    fun cacheRoundTrip() {
        val s = RemoteSettings(latestVersion = 30, updateReason = "r", updateReasonAr = "ر", updateSha256 = sha, requiredAfter = now, rolloutPercent = 50, pulledVersions = listOf(27, 28))
        assertEquals(s, RemoteSettings.decode(s.encode()))
    }

    @Test
    fun firestoreFields() {
        val json =
            """{"fields":{"latestVersion":{"integerValue":"30"},"rolloutPercent":{"integerValue":"150"},
            "requiredAfter":{"timestampValue":"2027-01-15T10:00:00Z"},"pulledVersions":{"arrayValue":{"values":[{"integerValue":"27"},{"stringValue":"x"}]}},
            "updateSha256":{"stringValue":"$sha"}}}"""
        val s = RemoteSettings.fromFirestore(json)
        assertEquals(100, s.rolloutPercent)
        assertEquals(listOf(27L), s.pulledVersions)
        assertEquals(1_800_000_000_000L > 0, s.requiredAfter > 0)
        assertEquals(sha, s.sha256OrEmpty)
        assertEquals(100, RemoteSettings.fromFirestore("""{"fields":{"latestVersion":{"integerValue":"3"}}}""").rolloutPercent)
    }

    @Test
    fun apkChecks() {
        assertTrue(UpdateChecks.hashMatches(sha.uppercase(), sha))
        assertFalse(UpdateChecks.hashMatches(sha, "short"))
        assertTrue(UpdateChecks.sameSigners(setOf("k1"), setOf("k0", "k1")))
        assertFalse(UpdateChecks.sameSigners(setOf("k1"), setOf("k2")))
        assertFalse(UpdateChecks.sameSigners(emptySet(), setOf("k2")))
    }
}
