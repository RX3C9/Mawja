/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class ModerationTest {
    private val now = 1_000_000L

    @Test
    fun emptyDocIsActive() {
        val m = Moderation.from(null)
        assertThat(m.blocked(now)).isFalse()
        assertThat(m.muted(now)).isFalse()
        assertThat(m.unseenMessage(null)).isNull()
        assertThat(Moderation.from(emptyMap<String, Any>())).isEqualTo(Moderation())
    }

    @Test
    fun bansAndTemporaryBans() {
        assertThat(Moderation(status = "banned").blocked(now)).isTrue()
        assertThat(Moderation(status = "suspended").blocked(now)).isTrue()
        assertThat(Moderation(status = "suspended").blockedUntil(now)).isNull()
        val temp = Moderation(status = "suspended", banUntil = now + 10)
        assertThat(temp.blocked(now)).isTrue()
        assertThat(temp.blockedUntil(now)).isEqualTo(now + 10)
        assertThat(temp.blocked(now + 11)).isFalse()
        assertThat(Moderation(status = "active", banUntil = now + 10).blocked(now)).isFalse()
    }

    @Test
    fun parsesFirestoreValues() {
        val doc =
            mapOf(
                "status" to "suspended",
                "banReason" to "spam",
                "banUntil" to Instant.ofEpochMilli(now + 5),
                "warnings" to 2L,
                "mutedUntil" to Instant.ofEpochMilli(now + 7),
                "lockedFeatures" to listOf("cloud_sync", "reports", 3),
                "adminMessage" to mapOf("id" to "m1", "text" to "hello", "at" to Instant.ofEpochMilli(now)),
                "signOutAt" to Instant.ofEpochMilli(now - 1),
            )
        val m = Moderation.from(doc)
        assertThat(m.banReason).isEqualTo("spam")
        assertThat(m.banUntil).isEqualTo(now + 5)
        assertThat(m.warnings).isEqualTo(2)
        assertThat(m.muted(now)).isTrue()
        assertThat(m.lockedFeatures).containsExactly("cloud_sync", "reports")
        assertThat(m.locked(MawjaFeature.CLOUD_SYNC)).isTrue()
        assertThat(m.message).isEqualTo(AdminMessage("m1", "hello", now))
        assertThat(m.unseenMessage("m1")).isNull()
        assertThat(m.unseenMessage("m0")?.id).isEqualTo("m1")
        assertThat(m.mustSignOut(now - 10)).isTrue()
        assertThat(m.mustSignOut(now)).isFalse()
        assertThat(m.mustSignOut(0)).isFalse()
    }

    @Test
    fun brokenMessageIsIgnored() {
        assertThat(Moderation.from(mapOf("adminMessage" to mapOf("id" to "", "text" to "x"))).message).isNull()
        assertThat(Moderation.from(mapOf("adminMessage" to mapOf("id" to "a"))).message).isNull()
    }

    @Test
    fun entitlementsFollowModeration() {
        val pro = Entitlements.forTier(MawjaTier.PRO)
        assertThat(pro.withModeration(Moderation(status = "banned"), now).features).isEmpty()
        val locked = pro.withModeration(Moderation(lockedFeatures = setOf("cloud_sync")), now)
        assertThat(locked.has(MawjaFeature.CLOUD_SYNC)).isFalse()
        assertThat(locked.has(MawjaFeature.UI_SOUNDS)).isTrue()
        assertThat(pro.withModeration(Moderation(), now)).isEqualTo(pro)
    }

    @Test
    fun lockableIdsCoverFeaturesReportsAndCodes() {
        assertThat(Moderation.LOCKABLE).containsAtLeast("cloud_sync", "premium_themes", "reports", "codes")
    }
}
