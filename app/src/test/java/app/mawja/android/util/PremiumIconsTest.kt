/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.util

import app.mawja.android.account.Entitlements
import app.mawja.android.account.MawjaTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PremiumIconsTest {
    private val free = Entitlements.FREE
    private val plus = Entitlements.forTier(MawjaTier.PLUS)

    @Test
    fun defaultIconIsAlwaysFree() {
        assertThat(PremiumIcons.isPremium(AppIcons.DEFAULT_SUFFIX)).isFalse()
        assertThat(PremiumIcons.canApply(AppIcons.DEFAULT_SUFFIX, free)).isTrue()
    }

    @Test
    fun onlyMawjaIconsArePremiumAndTheyAreRegistered() {
        assertThat(AppIcons.ALL_SUFFIXES).containsAtLeastElementsIn(AppIcons.PREMIUM_SUFFIXES)
        assertThat(AppIcons.PREMIUM_SUFFIXES).hasSize(4)
        AppIcons.ALL_SUFFIXES.filterNot { it in AppIcons.PREMIUM_SUFFIXES }.forEach {
            assertThat(PremiumIcons.isLocked(it, free)).isFalse()
        }
    }

    @Test
    fun premiumIconsNeedPlus() {
        AppIcons.PREMIUM_SUFFIXES.forEach { suffix ->
            assertThat(PremiumIcons.isLocked(suffix, free)).isTrue()
            assertThat(PremiumIcons.canApply(suffix, free)).isFalse()
            MawjaTier.entries.filter { it != MawjaTier.FREE }.forEach { tier ->
                assertThat(PremiumIcons.canApply(suffix, Entitlements.forTier(tier))).isTrue()
            }
        }
        assertThat(PremiumIcons.canApply(".IconSahara", plus)).isTrue()
    }

    @Test
    fun unknownSuffixIsNeverApplied() {
        assertThat(PremiumIcons.canApply(".IconNope", plus)).isFalse()
    }
}
