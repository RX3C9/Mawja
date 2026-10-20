/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.theme

import app.mawja.android.account.Entitlements
import app.mawja.android.account.MawjaFeature

/**
 * Which palettes are Mawja premium extras. Flow's own catalogue, Flow Default included, is always
 * free; only Mawja's additions sit behind [MawjaFeature.PREMIUM_THEMES].
 */
object PremiumThemes {
    /** Flow Default: what a locked palette falls back to. */
    val FALLBACK: ThemeMode = ThemeMode.DARK

    val modes: Set<ThemeMode> = setOf(ThemeMode.NILE)

    fun isPremium(mode: ThemeMode): Boolean = mode in modes

    fun isLocked(
        mode: ThemeMode,
        entitlements: Entitlements,
    ): Boolean = isPremium(mode) && !entitlements.has(MawjaFeature.PREMIUM_THEMES)

    /**
     * The palette actually shown. A stored premium palette the user no longer has (a downgrade, a
     * sign-out) shows as Flow Default; the stored choice is kept, so it comes back on re-upgrade.
     */
    fun effective(
        mode: ThemeMode,
        entitlements: Entitlements,
    ): ThemeMode = if (isLocked(mode, entitlements)) FALLBACK else mode
}
