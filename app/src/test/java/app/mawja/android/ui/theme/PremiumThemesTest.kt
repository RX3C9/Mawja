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
    fun mawjaDefaultIsFreeAndIsTheFallback() {
        // Since 678b816 the free Mawja (Nile) look is the default and the fallback; Flow's dark stays free.
        assertThat(PremiumThemes.FALLBACK).isEqualTo(ThemeMode.NILE)
        assertThat(PremiumThemes.isPremium(ThemeMode.NILE)).isFalse()
        assertThat(PremiumThemes.isLocked(ThemeMode.NILE, free)).isFalse()
        assertThat(PremiumThemes.isLocked(ThemeMode.DARK, free)).isFalse()
    }

    @Test
    fun onlyMawjaAdditionsArePremium() {
        ThemeMode.entries.filter { it !in PremiumThemes.modes }.forEach { mode ->
            assertThat(PremiumThemes.isLocked(mode, free)).isFalse()
            assertThat(PremiumThemes.effective(mode, free)).isEqualTo(mode)
        }
    }

    @Test
    fun mawjaDefaultNileIsFreeForEveryone() {
        assertThat(PremiumThemes.isPremium(ThemeMode.NILE)).isFalse()
        MawjaTier.entries.forEach { tier ->
            assertThat(PremiumThemes.isLocked(ThemeMode.NILE, Entitlements.forTier(tier))).isFalse()
        }
    }

    @Test
    fun downgradeFallsBackToMawjaDefaultAndUpgradeRestores() {
        assertThat(PremiumThemes.effective(ThemeMode.SAHARA, plus)).isEqualTo(ThemeMode.SAHARA)
        assertThat(PremiumThemes.effective(ThemeMode.SAHARA, free)).isEqualTo(ThemeMode.NILE)
        assertThat(PremiumThemes.effective(ThemeMode.SAHARA, plus)).isEqualTo(ThemeMode.SAHARA)
    }

    @Test
    fun mawjaVisualThemesArePlusOnly() {
        listOf(ThemeMode.SAHARA, ThemeMode.ANDALUS, ThemeMode.MIDNIGHT_OUD, ThemeMode.DELTA, ThemeMode.RED_SEA_REEF, ThemeMode.CAIRO_NIGHTS, ThemeMode.SIWA_OASIS, ThemeMode.ASWAN_SUNSET, ThemeMode.LOTUS, ThemeMode.ALEXANDRIA).forEach { mode ->
            assertThat(PremiumThemes.isPremium(mode)).isTrue()
            assertThat(PremiumThemes.effective(mode, free)).isEqualTo(ThemeMode.NILE)
            assertThat(PremiumThemes.effective(mode, plus)).isEqualTo(mode)
        }
    }

    @Test
    fun everyPremiumModeIsInThePicker() {
        val catalogued = ThemeCatalog.palettes.map { it.mode }.toSet()
        assertThat(catalogued).containsAtLeastElementsIn(PremiumThemes.modes)
    }
}
