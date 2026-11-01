/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.util

import app.mawja.android.account.Entitlements
import app.mawja.android.account.MawjaFeature

/**
 * Which launcher icons are Mawja premium extras ([MawjaFeature.PREMIUM_ICONS], Plus). Flow's own
 * icons stay free. An icon already applied is left in place after a downgrade (switching aliases
 * can drop home-screen shortcuts), it just cannot be picked again until re-upgrade.
 */
object PremiumIcons {
    fun isPremium(suffix: String): Boolean = AppIcons.isPremium(suffix)

    fun isLocked(
        suffix: String,
        entitlements: Entitlements,
    ): Boolean = isPremium(suffix) && !entitlements.has(MawjaFeature.PREMIUM_ICONS)

    /** Whether picking [suffix] may be applied now: never for a locked icon. */
    fun canApply(
        suffix: String,
        entitlements: Entitlements,
    ): Boolean = suffix in AppIcons.ALL_SUFFIXES && !isLocked(suffix, entitlements)
}
