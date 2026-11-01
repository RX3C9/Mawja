/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.theme

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mawja.android.accessibility.AccessibilityPolicy
import app.mawja.android.accessibility.ColorVisionMode
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #161 high contrast and #162 colour vision over every built-in palette and style. Robolectric: palettes tone through ColorUtils. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HighContrastSchemeTest {
    private fun ratio(
        a: Color,
        b: Color,
    ): Double = AccessibilityPolicy.contrastRatio(a.toArgb(), b.toArgb())

    /** The target, or the most black or white can give on [bg] when the target is out of reach. */
    private fun reachable(
        target: Double,
        bg: Color,
    ): Double = minOf(target, ratio(Color(AccessibilityPolicy.bestTextOn(bg.toArgb())), bg)) - 0.01

    private fun schemes() =
        ThemeCatalog.palettes.filterNot { it.mode == ThemeMode.MATERIAL_YOU }.flatMap { entry ->
            ThemeVariant.entries.map { variant ->
                "${entry.mode} $variant" to FlowPalettes.forMode(entry.mode).colorsFor(variant).toColorScheme(variant)
            }
        }

    @Test
    fun `high contrast text and outlines clear their targets in every palette`() {
        schemes().forEach { (name, base) ->
            val s = base.withHighContrast()
            listOf(s.surface, s.surfaceContainerHighest).forEach { bg ->
                assertWithMessage("$name onSurface").that(ratio(s.onSurface, bg)).isAtLeast(reachable(HighContrastTargets.BODY_TEXT, bg))
                assertWithMessage("$name onSurfaceVariant")
                    .that(ratio(s.onSurfaceVariant, bg))
                    .isAtLeast(reachable(HighContrastTargets.BODY_TEXT, bg))
                assertWithMessage("$name outline").that(ratio(s.outline, bg)).isAtLeast(reachable(HighContrastTargets.OUTLINE, bg))
                assertWithMessage("$name outlineVariant").that(ratio(s.outlineVariant, bg)).isAtLeast(reachable(HighContrastTargets.DIVIDER, bg))
                assertWithMessage("$name primary").that(ratio(s.primary, bg)).isAtLeast(reachable(HighContrastTargets.ACCENT, bg))
            }
            assertWithMessage("$name onPrimary").that(ratio(s.onPrimary, s.primary)).isAtLeast(reachable(HighContrastTargets.BODY_TEXT, s.primary))
            assertWithMessage("$name onPrimaryContainer")
                .that(ratio(s.onPrimaryContainer, s.primaryContainer))
                .isAtLeast(reachable(HighContrastTargets.BODY_TEXT, s.primaryContainer))
        }
    }

    @Test
    fun `high contrast never weakens a role that already passed`() {
        schemes().forEach { (name, base) ->
            val s = base.withHighContrast()
            assertWithMessage("$name onSurface").that(ratio(s.onSurface, s.surface)).isAtLeast(ratio(base.onSurface, base.surface) - 0.01)
            assertWithMessage("$name outline").that(ratio(s.outline, s.surface)).isAtLeast(ratio(base.outline, base.surface) - 0.01)
            assertWithMessage("$name surface kept").that(s.surface).isEqualTo(base.surface)
        }
    }

    @Test
    fun `colour vision swaps only the error pair`() {
        schemes().forEach { (name, base) ->
            val dark = base.surface.toArgb().let { AccessibilityPolicy.bestTextOn(it) } == Color.White.toArgb()
            assertWithMessage("$name default").that(base.withColorVision(ColorVisionMode.DEFAULT, dark)).isSameInstanceAs(base)
            val rg = base.withColorVision(ColorVisionMode.RED_GREEN, dark)
            assertWithMessage("$name error").that(rg.error.toArgb()).isEqualTo(AccessibilityPolicy.statePalette(ColorVisionMode.RED_GREEN, dark)!!.error)
            assertWithMessage("$name primary kept").that(rg.primary).isEqualTo(base.primary)
        }
    }
}
