/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EntitlementsTest {
    private fun signedIn(
        tier: MawjaTier,
        status: String = "active",
    ) = AccountState.SignedIn(
        MawjaProfile(
            uid = "u1",
            email = null,
            displayName = null,
            photoUrl = null,
            mawjaId = null,
            username = null,
            tier = tier,
            badges = emptyList(),
            status = status,
        ),
    )

    @Test
    fun freeHasNoPaidFeatures() {
        assertThat(Entitlements.forTier(MawjaTier.FREE).features).isEmpty()
        assertThat(Entitlements.FREE.isFree).isTrue()
    }

    @Test
    fun premiumThemesStartAtPlus() {
        MawjaTier.entries.forEach { tier ->
            val expected = tier != MawjaTier.FREE
            assertThat(Entitlements.forTier(tier).has(MawjaFeature.PREMIUM_THEMES)).isEqualTo(expected)
        }
        assertThat(Entitlements.minimumTier(MawjaFeature.PREMIUM_THEMES)).isEqualTo(MawjaTier.PLUS)
    }

    @Test
    fun higherTiersIncludeLowerTiers() {
        val plus = Entitlements.forTier(MawjaTier.PLUS).features
        val pro = Entitlements.forTier(MawjaTier.PRO).features
        assertThat(pro).containsAtLeastElementsIn(plus)
        assertThat(Entitlements.forTier(MawjaTier.LIFETIME).features).containsExactlyElementsIn(pro)
        assertThat(Entitlements.forTier(MawjaTier.FAMILY).features).containsAtLeastElementsIn(pro)
        assertThat(Entitlements.forTier(MawjaTier.FAMILY).has(MawjaFeature.FAMILY_SHARING)).isTrue()
        assertThat(Entitlements.forTier(MawjaTier.PRO).has(MawjaFeature.FAMILY_SHARING)).isFalse()
    }

    @Test
    fun guestIsAlwaysFreeEvenWithACachedTier() {
        assertThat(effectiveTier(AccountState.Guest, MawjaTier.PRO)).isEqualTo(MawjaTier.FREE)
    }

    @Test
    fun loadingUsesCachedTierOrFree() {
        assertThat(effectiveTier(AccountState.Loading, MawjaTier.PLUS)).isEqualTo(MawjaTier.PLUS)
        assertThat(effectiveTier(AccountState.Loading, null)).isEqualTo(MawjaTier.FREE)
    }

    @Test
    fun signedInUsesProfileTierUnlessRestricted() {
        assertThat(effectiveTier(signedIn(MawjaTier.PLUS), MawjaTier.FREE)).isEqualTo(MawjaTier.PLUS)
        assertThat(effectiveTier(signedIn(MawjaTier.PRO, status = "suspended"), null)).isEqualTo(MawjaTier.FREE)
        assertThat(effectiveTier(signedIn(MawjaTier.PRO, status = "BANNED"), null)).isEqualTo(MawjaTier.FREE)
    }

    @Test
    fun missingProfileTierMeansFree() {
        // The backend's myProfile function may be unavailable; an unknown tier id parses as Free.
        assertThat(MawjaTier.of(null)).isEqualTo(MawjaTier.FREE)
        assertThat(MawjaTier.of("gold")).isEqualTo(MawjaTier.FREE)
        assertThat(effectiveTier(signedIn(MawjaTier.of(null)), MawjaTier.PRO)).isEqualTo(MawjaTier.FREE)
    }

    @Test
    fun cacheIsKeptWhileLoadingAndClearedOnSignOut() {
        assertThat(tierToCache(AccountState.Loading)).isNull()
        assertThat(tierToCache(AccountState.Guest)).isEqualTo(MawjaTier.FREE)
        assertThat(tierToCache(signedIn(MawjaTier.LIFETIME))).isEqualTo(MawjaTier.LIFETIME)
    }
}
