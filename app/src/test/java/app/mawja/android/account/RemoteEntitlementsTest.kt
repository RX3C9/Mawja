/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import app.mawja.remote.RemoteSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RemoteEntitlementsTest {
    @Test
    fun `default config leaves the table unchanged`() {
        MawjaTier.entries.forEach { tier ->
            val table = Entitlements.forTier(tier)
            assertThat(table.withRemote(RemoteSettings.DEFAULT)).isEqualTo(table)
        }
    }

    @Test
    fun `premiumThemes switch off removes premium themes for every tier`() {
        val off = RemoteSettings(features = mapOf("premiumThemes" to false))
        val pro = Entitlements.forTier(MawjaTier.PRO).withRemote(off)
        assertThat(pro.has(MawjaFeature.PREMIUM_THEMES)).isFalse()
        assertThat(pro.has(MawjaFeature.VISUALIZER)).isTrue()
        assertThat(pro.tier).isEqualTo(MawjaTier.PRO)
    }

    @Test
    fun `a tier gate can lower or raise a feature's tier`() {
        val gates = RemoteSettings(tierGates = mapOf("VISUALIZER" to "plus", "PREMIUM_ICONS" to "pro"))
        val plus = Entitlements.forTier(MawjaTier.PLUS).withRemote(gates)
        assertThat(plus.has(MawjaFeature.VISUALIZER)).isTrue()
        assertThat(plus.has(MawjaFeature.PREMIUM_ICONS)).isFalse()
        assertThat(plus.has(MawjaFeature.PREMIUM_THEMES)).isTrue()
        assertThat(Entitlements.FREE.withRemote(gates).features).isEmpty()
    }

    @Test
    fun `an unknown tier in a gate is ignored`() {
        val gates = RemoteSettings(tierGates = mapOf("UI_SOUNDS" to "gold"))
        assertThat(Entitlements.forTier(MawjaTier.PRO).withRemote(gates).has(MawjaFeature.UI_SOUNDS)).isTrue()
        assertThat(Entitlements.forTier(MawjaTier.PLUS).withRemote(gates).has(MawjaFeature.UI_SOUNDS)).isFalse()
    }
}
