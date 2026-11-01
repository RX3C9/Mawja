/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import app.mawja.android.accessibility.AccessibilityPolicy
import app.mawja.android.accessibility.ColorVisionMode

/** Minimum contrast ratios the high-contrast option guarantees (WCAG AAA text, AA large text and UI parts). */
internal object HighContrastTargets {
    const val BODY_TEXT = 7.0
    const val ACCENT = 4.5
    const val OUTLINE = 4.5
    const val DIVIDER = 3.0
}

private fun Color.opaqueOn(ground: Color): Color = if (alpha >= 1f) this else compositeOver(ground)

private fun Color.ensuring(
    background: Color,
    ratio: Double,
): Color {
    val bg = background.opaqueOn(Color.Black)
    return Color(AccessibilityPolicy.ensureContrast(opaqueOn(bg).toArgb(), bg.toArgb(), ratio))
}

/**
 * The scheme with every text, icon and outline role strengthened against the surface it sits on (#161).
 * Surfaces keep their colours, so the user's theme is still recognisable; what changes is how far text,
 * secondary text, outlines and dividers stand off it. Text is checked against both the base surface and
 * the highest container, the two ends of the surface ladder cards and sheets use.
 */
internal fun ColorScheme.withHighContrast(): ColorScheme {
    fun onSurfaces(
        color: Color,
        ratio: Double,
    ): Color = color.ensuring(surface, ratio).ensuring(surfaceContainerHighest, ratio).ensuring(background, ratio)

    return copy(
        onSurface = onSurfaces(onSurface, HighContrastTargets.BODY_TEXT),
        onBackground = onSurfaces(onBackground, HighContrastTargets.BODY_TEXT),
        onSurfaceVariant = onSurfaces(onSurfaceVariant, HighContrastTargets.BODY_TEXT),
        outline = onSurfaces(outline, HighContrastTargets.OUTLINE),
        outlineVariant = onSurfaces(outlineVariant, HighContrastTargets.DIVIDER),
        primary = onSurfaces(primary, HighContrastTargets.ACCENT),
        secondary = onSurfaces(secondary, HighContrastTargets.ACCENT),
        tertiary = onSurfaces(tertiary, HighContrastTargets.ACCENT),
        error = onSurfaces(error, HighContrastTargets.ACCENT),
    ).let { s ->
        s.copy(
            onPrimary = s.onPrimary.ensuring(s.primary, HighContrastTargets.BODY_TEXT),
            onSecondary = s.onSecondary.ensuring(s.secondary, HighContrastTargets.BODY_TEXT),
            onTertiary = s.onTertiary.ensuring(s.tertiary, HighContrastTargets.BODY_TEXT),
            onError = s.onError.ensuring(s.error, HighContrastTargets.BODY_TEXT),
            onPrimaryContainer = s.onPrimaryContainer.ensuring(s.primaryContainer, HighContrastTargets.BODY_TEXT),
            onSecondaryContainer = s.onSecondaryContainer.ensuring(s.secondaryContainer, HighContrastTargets.BODY_TEXT),
            onTertiaryContainer = s.onTertiaryContainer.ensuring(s.tertiaryContainer, HighContrastTargets.BODY_TEXT),
            onErrorContainer = s.onErrorContainer.ensuring(s.errorContainer, HighContrastTargets.BODY_TEXT),
        )
    }
}

/** The scheme's error role swapped for one a colour-blind user tells apart from success (#162). */
internal fun ColorScheme.withColorVision(
    mode: ColorVisionMode,
    dark: Boolean,
): ColorScheme {
    val palette = AccessibilityPolicy.statePalette(mode, dark) ?: return this
    return copy(error = Color(palette.error), onError = Color(palette.onError))
}

/** The success colour for [mode], or [themeSuccess] when the mode keeps theme colours. */
internal fun accessibleSuccess(
    themeSuccess: Color,
    mode: ColorVisionMode,
    dark: Boolean,
): Color = AccessibilityPolicy.statePalette(mode, dark)?.let { Color(it.success) } ?: themeSuccess

/**
 * Motion for reduce motion (#163): every Material component transition lands in its end state at
 * once instead of springing there. Feedback the user needs, such as a ripple or a progress bar
 * moving, is not part of the motion scheme and still shows.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object ReducedMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}
