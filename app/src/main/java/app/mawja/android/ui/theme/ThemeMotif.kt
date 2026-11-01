/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.theme

/**
 * A faint pattern drawn behind a premium palette's swatch in the theme picker, so the Mawja
 * palettes read as their place at a glance. Purely decorative; it never tints the app itself.
 */
enum class ThemeMotif {
    /** Rolling dune lines (Sahara). */
    DUNES,

    /** Eight-point zellige stars (Andalus). */
    ZELLIGE,

    /** Scattered night-sky stars (Midnight Oud). */
    STARS,

    /** Papyrus stems (Delta). */
    PAPYRUS,

    /** Reef bubbles (Red Sea Reef). */
    REEF,

    /** Nile ripples (Nile). */
    RIPPLES,
    ;

    companion object {
        fun forMode(mode: ThemeMode): ThemeMotif? =
            when (mode) {
                ThemeMode.SAHARA -> DUNES
                ThemeMode.ANDALUS -> ZELLIGE
                ThemeMode.MIDNIGHT_OUD -> STARS
                ThemeMode.DELTA -> PAPYRUS
                ThemeMode.RED_SEA_REEF -> REEF
                ThemeMode.NILE -> RIPPLES
                else -> null
            }
    }
}
