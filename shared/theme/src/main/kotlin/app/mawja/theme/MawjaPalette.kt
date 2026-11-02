/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** The colours one palette is built from; the same roles as the main app's palette seeds. */
data class Seed(
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val error: Color,
)

/**
 * Mawja's shared palettes: the free Mawja (Nile) look and the ten premium palettes, the same in the
 * app, the Downloader and the admin app. Stored by [storageId]; never rename one.
 */
enum class MawjaPalette(
    val storageId: String,
    val light: Seed,
    val dark: Seed,
) {
    NILE(
        storageId = "nile",
        light = Seed(primary = Color(0xFF00897B), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFFE2603F), background = Color(0xFFF6FAFA), surface = Color(0xFFE7F2F1), onSurface = Color(0xFF0B1020), onSurfaceVariant = Color(0xFF4F5D6B), outline = Color(0xFFC3D3D3), error = Color(0xFFBA1A1A)),
        dark = Seed(primary = Color(0xFF19C3B1), onPrimary = Color(0xFF00201C), secondary = Color(0xFFFF7A59), background = Color(0xFF0B1020), surface = Color(0xFF151C30), onSurface = Color(0xFFEAF2F5), onSurfaceVariant = Color(0xFFA9B6C6), outline = Color(0xFF2B3550), error = Color(0xFFFFB4AB)),
    ),
    SAHARA(
        storageId = "sahara",
        light = Seed(primary = Color(0xFF9A5B00), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFFB5651D), background = Color(0xFFFBF5EA), surface = Color(0xFFF3E8D3), onSurface = Color(0xFF2B1D0E), onSurfaceVariant = Color(0xFF65523A), outline = Color(0xFFDECBA8), error = Color(0xFFB3261E)),
        dark = Seed(primary = Color(0xFFF2B54A), onPrimary = Color(0xFF2E1D00), secondary = Color(0xFFE08A4F), background = Color(0xFF17120B), surface = Color(0xFF231B11), onSurface = Color(0xFFF6EBD9), onSurfaceVariant = Color(0xFFCDBB9C), outline = Color(0xFF47392A), error = Color(0xFFFFB4AB)),
    ),
    ANDALUS(
        storageId = "andalus",
        light = Seed(primary = Color(0xFF00695C), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFFB5502E), background = Color(0xFFF7F6F1), surface = Color(0xFFEAEFEA), onSurface = Color(0xFF14241F), onSurfaceVariant = Color(0xFF4C5D57), outline = Color(0xFFC9D6CF), error = Color(0xFFBA1A1A)),
        dark = Seed(primary = Color(0xFF4DC2B0), onPrimary = Color(0xFF00201B), secondary = Color(0xFFE8875F), background = Color(0xFF0D1716), surface = Color(0xFF16211F), onSurface = Color(0xFFE7F1EE), onSurfaceVariant = Color(0xFFA9BDB7), outline = Color(0xFF2C3B38), error = Color(0xFFFFB4AB)),
    ),
    MIDNIGHT_OUD(
        storageId = "midnight_oud",
        light = Seed(primary = Color(0xFF6B2E7A), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF8C6A1F), background = Color(0xFFFAF6F8), surface = Color(0xFFF0E7EE), onSurface = Color(0xFF24142A), onSurfaceVariant = Color(0xFF5E4B63), outline = Color(0xFFDCCDD9), error = Color(0xFFB3261E)),
        dark = Seed(primary = Color(0xFFE2B85C), onPrimary = Color(0xFF2A1C00), secondary = Color(0xFFC79BD6), background = Color(0xFF140A17), surface = Color(0xFF1F1224), onSurface = Color(0xFFF4E9F2), onSurfaceVariant = Color(0xFFC7B3CB), outline = Color(0xFF3C2A41), error = Color(0xFFFFB4AB)),
    ),
    DELTA(
        storageId = "delta",
        light = Seed(primary = Color(0xFF2F6B2F), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF8A6D2E), background = Color(0xFFF8F7EE), surface = Color(0xFFECEEDD), onSurface = Color(0xFF1A2414), onSurfaceVariant = Color(0xFF4E5944), outline = Color(0xFFCCD3B9), error = Color(0xFFBA1A1A)),
        dark = Seed(primary = Color(0xFF8CCB7A), onPrimary = Color(0xFF0E2A06), secondary = Color(0xFFD9C27E), background = Color(0xFF0F150D), surface = Color(0xFF182016), onSurface = Color(0xFFEAF0E2), onSurfaceVariant = Color(0xFFB3BFA6), outline = Color(0xFF2F3A2A), error = Color(0xFFFFB4AB)),
    ),
    RED_SEA_REEF(
        storageId = "red_sea_reef",
        light = Seed(primary = Color(0xFFB8402F), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF00838F), background = Color(0xFFF7FBFB), surface = Color(0xFFE8F3F4), onSurface = Color(0xFF0E2229), onSurfaceVariant = Color(0xFF4A5F66), outline = Color(0xFFC4D9DC), error = Color(0xFFA8201A)),
        dark = Seed(primary = Color(0xFFFF8A70), onPrimary = Color(0xFF3A0A00), secondary = Color(0xFF4DD0E1), background = Color(0xFF081A20), surface = Color(0xFF10262D), onSurface = Color(0xFFE6F4F6), onSurfaceVariant = Color(0xFFA6C1C7), outline = Color(0xFF23404A), error = Color(0xFFFFB4AB)),
    ),
    CAIRO_NIGHTS(
        storageId = "cairo_nights",
        light = Seed(primary = Color(0xFF814BCB), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF927215), background = Color(0xFFFBFAFC), surface = Color(0xFFF1F1F6), onSurface = Color(0xFF201E2C), onSurfaceVariant = Color(0xFF5D5681), outline = Color(0xFFD6D3E5), error = Color(0xFFA8201A)),
        dark = Seed(primary = Color(0xFFBB9FE3), onPrimary = Color(0xFF241040), secondary = Color(0xFFE4BA3F), background = Color(0xFF181524), surface = Color(0xFF242036), onSurface = Color(0xFFF1F1F5), onSurfaceVariant = Color(0xFFBDBAD1), outline = Color(0xFF3E375C), error = Color(0xFFFFB4AB)),
    ),
    SIWA_OASIS(
        storageId = "siwa_oasis",
        light = Seed(primary = Color(0xFF2D774C), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF9E6D27), background = Color(0xFFF8FBF8), surface = Color(0xFFECF3EC), onSurface = Color(0xFF172317), onSurfaceVariant = Color(0xFF426342), outline = Color(0xFFC4DCC4), error = Color(0xFFA8201A)),
        dark = Seed(primary = Color(0xFF55BE81), onPrimary = Color(0xFF082012), secondary = Color(0xFFE0B882), background = Color(0xFF101B10), surface = Color(0xFF172717), onSurface = Color(0xFFEDF3ED), onSurfaceVariant = Color(0xFFA7C4A7), outline = Color(0xFF284328), error = Color(0xFFFFB4AB)),
    ),
    ASWAN_SUNSET(
        storageId = "aswan_sunset",
        light = Seed(primary = Color(0xFFAB4D1E), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFFC34A86), background = Color(0xFFFBFAF9), surface = Color(0xFFF4F0EE), onSurface = Color(0xFF261E19), onSurfaceVariant = Color(0xFF6D5748), outline = Color(0xFFDFD2CA), error = Color(0xFFA8201A)),
        dark = Seed(primary = Color(0xFFE69972), onPrimary = Color(0xFF2B160B), secondary = Color(0xFFE5B1CB), background = Color(0xFF1D1612), surface = Color(0xFF2C211A), onSurface = Color(0xFFF4F1EE), onSurfaceVariant = Color(0xFFC9B9AE), outline = Color(0xFF4A392C), error = Color(0xFFFFB4AB)),
    ),
    LOTUS(
        storageId = "lotus",
        light = Seed(primary = Color(0xFFAA4187), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF328376), background = Color(0xFFFCF9FC), surface = Color(0xFFF5EFF5), onSurface = Color(0xFF291B28), onSurfaceVariant = Color(0xFF754E75), outline = Color(0xFFE3D0E3), error = Color(0xFFA8201A)),
        dark = Seed(primary = Color(0xFFD797C2), onPrimary = Color(0xFF330D26), secondary = Color(0xFF7CCDC0), background = Color(0xFF201420), surface = Color(0xFF301D30), onSurface = Color(0xFFF5F0F5), onSurfaceVariant = Color(0xFFCDB5CD), outline = Color(0xFF513051), error = Color(0xFFFFB4AB)),
    ),
    ALEXANDRIA(
        storageId = "alexandria",
        light = Seed(primary = Color(0xFF286E9F), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFFC25531), background = Color(0xFFF9FAFB), surface = Color(0xFFEDF1F4), onSurface = Color(0xFF182025), onSurfaceVariant = Color(0xFF485F6B), outline = Color(0xFFC9D8DF), error = Color(0xFFA8201A)),
        dark = Seed(primary = Color(0xFF75B1DD), onPrimary = Color(0xFF0B1D2A), secondary = Color(0xFFE8B3A2), background = Color(0xFF11181C), surface = Color(0xFF19242A), onSurface = Color(0xFFEEF2F3), onSurfaceVariant = Color(0xFFADC0C9), outline = Color(0xFF2B3E48), error = Color(0xFFFFB4AB)),
    ),
    ;

    /** Every palette except the free default is a premium extra. */
    val isPremium: Boolean get() = this != NILE

    companion object {
        fun fromStorage(id: String?): MawjaPalette = entries.firstOrNull { it.storageId == id } ?: NILE
    }
}

/** A complete Material 3 scheme from a palette; containers are blended from its seed. */
fun mawjaColorScheme(
    palette: MawjaPalette,
    dark: Boolean,
): ColorScheme {
    val s = if (dark) palette.dark else palette.light
    val primaryContainer = lerp(s.surface, s.primary, if (dark) 0.28f else 0.18f)
    val secondaryContainer = lerp(s.surface, s.secondary, if (dark) 0.26f else 0.16f)

    fun step(f: Float) = lerp(s.background, s.onSurface, f)
    val outline = lerp(s.outline, s.onSurfaceVariant, 0.5f)
    return if (dark) {
        darkColorScheme(
            primary = s.primary,
            onPrimary = s.onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = s.onSurface,
            secondary = s.secondary,
            onSecondary = s.background,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = s.onSurface,
            background = s.background,
            onBackground = s.onSurface,
            surface = s.background,
            onSurface = s.onSurface,
            surfaceVariant = s.surface,
            onSurfaceVariant = s.onSurfaceVariant,
            surfaceContainerLowest = step(0f),
            surfaceContainerLow = step(0.03f),
            surfaceContainer = s.surface,
            surfaceContainerHigh = step(0.1f),
            surfaceContainerHighest = step(0.14f),
            outline = outline,
            outlineVariant = s.outline,
            error = s.error,
        )
    } else {
        lightColorScheme(
            primary = s.primary,
            onPrimary = s.onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = s.onSurface,
            secondary = s.secondary,
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = s.onSurface,
            background = s.background,
            onBackground = s.onSurface,
            surface = s.background,
            onSurface = s.onSurface,
            surfaceVariant = s.surface,
            onSurfaceVariant = s.onSurfaceVariant,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = step(0.02f),
            surfaceContainer = s.surface,
            surfaceContainerHigh = step(0.07f),
            surfaceContainerHighest = step(0.1f),
            outline = outline,
            outlineVariant = s.outline,
            error = s.error,
        )
    }
}
