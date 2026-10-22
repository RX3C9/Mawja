/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.account

import app.mawja.remote.RemoteSettings

/** Paid extras a membership can unlock. Everything the original app does stays free. */
enum class MawjaFeature {
    /** Optional palettes beyond Flow's own catalogue, such as Nile. */
    PREMIUM_THEMES,

    /** Premium app icons. */
    PREMIUM_ICONS,

    /** UI sound effects pack. */
    UI_SOUNDS,

    /** Audio visualizer and animated backgrounds. */
    VISUALIZER,

    /** Cloud sync of the library between the member's devices (PLAN_V2 #61). */
    CLOUD_SYNC,

    /** Shared plan for up to six people. */
    FAMILY_SHARING,
}

/** What the current user may use, derived from their [tier]. */
data class Entitlements(
    val tier: MawjaTier,
    val features: Set<MawjaFeature>,
) {
    fun has(feature: MawjaFeature): Boolean = feature in features

    val isFree: Boolean get() = tier == MawjaTier.FREE

    companion object {
        private val PLUS_FEATURES = setOf(MawjaFeature.PREMIUM_THEMES, MawjaFeature.PREMIUM_ICONS, MawjaFeature.CLOUD_SYNC)
        private val PRO_FEATURES = PLUS_FEATURES + setOf(MawjaFeature.UI_SOUNDS, MawjaFeature.VISUALIZER)

        val FREE = Entitlements(MawjaTier.FREE, emptySet())

        /** The fixed tier -> feature table. The server stays the source of truth for the tier itself. */
        fun forTier(tier: MawjaTier): Entitlements =
            when (tier) {
                MawjaTier.FREE -> FREE
                MawjaTier.PLUS -> Entitlements(tier, PLUS_FEATURES)
                MawjaTier.PRO, MawjaTier.LIFETIME -> Entitlements(tier, PRO_FEATURES)
                MawjaTier.FAMILY -> Entitlements(tier, PRO_FEATURES + MawjaFeature.FAMILY_SHARING)
            }

        /** The lowest tier that unlocks [feature], for "available in Plus" style copy. */
        fun minimumTier(feature: MawjaFeature): MawjaTier =
            listOf(MawjaTier.PLUS, MawjaTier.PRO, MawjaTier.FAMILY).first { forTier(it).has(feature) }
    }
}

/**
 * Applies config/app on top of the fixed table: a remote tier gate (keyed by [MawjaFeature] name)
 * replaces the table's rule for that feature, and the premiumThemes switch turns Premium themes off
 * for everyone. Without a remote config this is the table unchanged.
 */
fun Entitlements.withRemote(remote: RemoteSettings): Entitlements {
    val allowed =
        MawjaFeature.entries
            .filter { feature -> remote.tierAllows(feature.name, tier.id) ?: (feature in features) }
            .filterNot { it == MawjaFeature.PREMIUM_THEMES && !remote.isEnabled(PREMIUM_THEMES_SWITCH) }
            .toSet()
    return if (allowed == features) this else copy(features = allowed)
}

/** config/app features key that switches Premium themes on or off. */
const val PREMIUM_THEMES_SWITCH = "premiumThemes"

/** Account statuses that keep guest features but lose every paid extra. */
private val RESTRICTED_STATUSES = setOf("suspended", "banned", "disabled")

/**
 * Which tier applies right now. Guests, restricted accounts and anything unknown are Free; while a
 * signed-in session is still restoring ([AccountState.Loading]) the last tier seen on this device
 * is used, so a member is not flashed back to Free on every cold start.
 */
fun effectiveTier(
    state: AccountState,
    cachedTier: MawjaTier?,
): MawjaTier =
    when (state) {
        AccountState.Guest -> MawjaTier.FREE
        AccountState.Loading -> cachedTier ?: MawjaTier.FREE
        is AccountState.SignedIn ->
            if (state.profile.status.lowercase() in RESTRICTED_STATUSES) MawjaTier.FREE else state.profile.tier
    }

/** The tier worth remembering for the next cold start: null keeps what is cached. */
fun tierToCache(state: AccountState): MawjaTier? =
    when (state) {
        AccountState.Loading -> null
        else -> effectiveTier(state, null)
    }
