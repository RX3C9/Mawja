/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.sound

import app.mawja.android.account.Entitlements
import app.mawja.android.account.MawjaFeature
import app.mawja.android.account.MawjaTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UiSoundPolicyTest {
    private val allOn =
        UiSoundConditions(enabled = true, entitled = true, systemTouchSounds = true, ringerNormal = true, doNotDisturb = false)

    @Test
    fun playsOnlyWhenEverythingAllows() {
        assertThat(UiSoundPolicy.shouldPlay(allOn)).isTrue()
    }

    @Test
    fun offByDefaultSwitchSilences() {
        assertThat(UiSoundPolicy.shouldPlay(allOn.copy(enabled = false))).isFalse()
    }

    @Test
    fun needsTheProFeature() {
        assertThat(UiSoundPolicy.shouldPlay(allOn.copy(entitled = false))).isFalse()
        assertThat(Entitlements.FREE.has(MawjaFeature.UI_SOUNDS)).isFalse()
        assertThat(Entitlements.forTier(MawjaTier.PLUS).has(MawjaFeature.UI_SOUNDS)).isFalse()
        assertThat(Entitlements.forTier(MawjaTier.PRO).has(MawjaFeature.UI_SOUNDS)).isTrue()
    }

    @Test
    fun respectsSystemTouchSoundsSilentAndDnd() {
        assertThat(UiSoundPolicy.shouldPlay(allOn.copy(systemTouchSounds = false))).isFalse()
        assertThat(UiSoundPolicy.shouldPlay(allOn.copy(ringerNormal = false))).isFalse()
        assertThat(UiSoundPolicy.shouldPlay(allOn.copy(doNotDisturb = true))).isFalse()
    }

    @Test
    fun volumeStaysLow() {
        assertThat(UiSoundPolicy.VOLUME).isAtMost(0.3f)
        assertThat(UiSoundPolicy.VOLUME).isGreaterThan(0f)
    }

    @Test
    fun cooldownThrottlesRapidSounds() {
        assertThat(UiSoundPolicy.outsideCooldown(0L, 5L)).isTrue()
        assertThat(UiSoundPolicy.outsideCooldown(1_000L, 1_010L)).isFalse()
        assertThat(UiSoundPolicy.outsideCooldown(1_000L, 1_000L + UiSoundPolicy.MIN_INTERVAL_MS)).isTrue()
    }
}
