/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.theme

import app.mawja.android.account.Entitlements
import app.mawja.android.account.MawjaTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PremiumThemesTest {
    private val free = Entitlements.FREE
    private val plus = Entitlements.forTier(MawjaTier.PLUS)

    @Test
    fun flowDefaultIsFreeAndIsTheFallback() {
        assertThat(PremiumThemes.FALLBACK).isEqualTo(ThemeMode.DARK)
        assertThat(PremiumThemes.isPremium(ThemeMode.DARK)).isFalse()
        assertThat(PremiumThemes.isLocked(ThemeMode.DARK, free)).isFalse()
    }

    @Test
    fun onlyMawjaAdditionsArePremium() {
        ThemeMode.entries.filter { it != ThemeMode.NILE }.forEach { mode ->
            assertThat(PremiumThemes.isLocked(mode, free)).isFalse()
            assertThat(PremiumThemes.effective(mode, free)).isEqualTo(mode)
        }
    }

    @Test
    fun nileIsLockedForFreeAndOpenForPlus() {
        assertThat(PremiumThemes.isLocked(ThemeMode.NILE, free)).isTrue()
        assertThat(PremiumThemes.isLocked(ThemeMode.NILE, plus)).isFalse()
        MawjaTier.entries.filter { it != MawjaTier.FREE }.forEach { tier ->
            assertThat(PremiumThemes.isLocked(ThemeMode.NILE, Entitlements.forTier(tier))).isFalse()
        }
    }

    @Test
    fun downgradeFallsBackToFlowDefaultAndUpgradeRestores() {
        assertThat(PremiumThemes.effective(ThemeMode.NILE, plus)).isEqualTo(ThemeMode.NILE)
        assertThat(PremiumThemes.effective(ThemeMode.NILE, free)).isEqualTo(ThemeMode.DARK)
        // The stored choice is untouched, so the same input resolves to Nile again after re-upgrading.
        assertThat(PremiumThemes.effective(ThemeMode.NILE, plus)).isEqualTo(ThemeMode.NILE)
    }
}
