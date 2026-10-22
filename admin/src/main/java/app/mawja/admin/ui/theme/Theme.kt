/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.admin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Mawja brand: deep teal wave with a gold accent (gold = admin). */
object Brand {
    val Gold = Color(0xFFF2C14E)
    val GoldDark = Color(0xFF7A5A00)
    val Teal = Color(0xFF2BB3A3)
    val TealDark = Color(0xFF00695F)
    val Danger = Color(0xFFE5484D)
    val Success = Color(0xFF30A46C)

    fun tierColor(tier: String): Color =
        when (tier) {
            "plus" -> Color(0xFF3E8EF7)
            "pro" -> Color(0xFF8E4EC6)
            "family" -> Color(0xFF2BB3A3)
            "lifetime" -> Gold
            else -> Color(0xFF8B8D98)
        }
}

private val Dark =
    darkColorScheme(
        primary = Brand.Gold,
        onPrimary = Color(0xFF261A00),
        primaryContainer = Color(0xFF3D2F00),
        onPrimaryContainer = Color(0xFFFFE08B),
        secondary = Brand.Teal,
        onSecondary = Color(0xFF00201C),
        secondaryContainer = Color(0xFF00504A),
        onSecondaryContainer = Color(0xFF9EF2E4),
        background = Color(0xFF0E0F11),
        onBackground = Color(0xFFE6E6E9),
        surface = Color(0xFF0E0F11),
        onSurface = Color(0xFFE6E6E9),
        surfaceVariant = Color(0xFF2A2C31),
        onSurfaceVariant = Color(0xFFC3C5CC),
        surfaceContainerLowest = Color(0xFF09090B),
        surfaceContainerLow = Color(0xFF15161A),
        surfaceContainer = Color(0xFF1A1B20),
        surfaceContainerHigh = Color(0xFF22232A),
        surfaceContainerHighest = Color(0xFF2C2D34),
        outline = Color(0xFF8D9099),
        outlineVariant = Color(0xFF41434A),
        error = Color(0xFFFF8A80),
    )

private val Light =
    lightColorScheme(
        primary = Brand.GoldDark,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFE08B),
        onPrimaryContainer = Color(0xFF251A00),
        secondary = Brand.TealDark,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFA5F2E6),
        onSecondaryContainer = Color(0xFF00201C),
        background = Color(0xFFFBF9F4),
        surface = Color(0xFFFBF9F4),
        surfaceContainerLow = Color(0xFFF5F2EB),
        surfaceContainer = Color(0xFFEFECE5),
        surfaceContainerHigh = Color(0xFFE9E6DF),
        surfaceContainerHighest = Color(0xFFE3E0D9),
    )

@Composable
fun AdminTheme(
    theme: String,
    content: @Composable () -> Unit,
) {
    val dark =
        when (theme) {
            "dark" -> true
            "light" -> false
            else -> isSystemInDarkTheme()
        }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
