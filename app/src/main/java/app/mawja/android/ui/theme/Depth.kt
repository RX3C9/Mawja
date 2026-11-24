/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.ui.theme

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of the Mawja "Nile Glass" depth a screen may draw right now (see AGENTS.md, "Depth and
 * glass exception"). [FULL] draws translucent glass, the edge highlight and the deepest elevation;
 * [REDUCED] keeps the plain opaque look with a softer elevation; [OFF] draws nothing extra.
 */
enum class EffectLevel {
    FULL,
    REDUCED,
    OFF,
}

/**
 * Decides the [EffectLevel] from the device and the user's settings. Pure, so every rule is unit
 * tested (EffectBudgetTest) and the composable side only gathers the inputs.
 */
object EffectBudget {
    /** RenderEffect (and so `Modifier.blur`) exists from Android 12 (API 31). */
    const val MIN_BLUR_SDK = 31

    fun level(
        premiumTheme: Boolean,
        lowRamDevice: Boolean,
        batterySaverActive: Boolean,
        reduceMotion: Boolean,
        highContrast: Boolean = false,
    ): EffectLevel =
        when {
            // Depth is a premium-theme look only, and high contrast always wants solid surfaces.
            !premiumTheme || highContrast -> EffectLevel.OFF
            // A small device that is also saving battery gets nothing extra at all.
            lowRamDevice && batterySaverActive -> EffectLevel.OFF
            lowRamDevice || batterySaverActive || reduceMotion -> EffectLevel.REDUCED
            else -> EffectLevel.FULL
        }

    /** Whether a real blur may be drawn: full budget on API 31+. Below that the glass uses [MawjaDepth.scrimAlpha]. */
    fun allowsBlur(
        level: EffectLevel,
        sdkInt: Int,
    ): Boolean = level == EffectLevel.FULL && sdkInt >= MIN_BLUR_SDK
}

/**
 * The depth tokens. Every depth, glass or glow effect in the app reads these values; none are
 * hand-tuned at a call site. Colours always come from the colour scheme, only their alpha is here.
 */
@Immutable
data class MawjaDepth(
    /** Resting cards and rows. */
    val level1: Dp = 2.dp,
    /** Floating chrome: the mini player. */
    val level2: Dp = 6.dp,
    /** The floating navigation capsule. */
    val level3: Dp = 14.dp,
    /** Blur radius for a static backdrop layer drawn under glass (API 31+ only). */
    val blurRadius: Dp = 24.dp,
    /** Opacity of a glass container at full budget on API 31+ (where a blurred static backdrop may sit behind it). */
    val glassAlpha: Float = 0.90f,
    /** Opacity of a glass container without blur (API 26-30): nearly solid so text stays readable. */
    val scrimAlpha: Float = 0.94f,
    /** Opacity of the light hairline on a glass edge. */
    val edgeGlowAlpha: Float = 0.18f,
    /** Opacity of the accent tint in a glass surface's shadow. */
    val shadowTintAlpha: Float = 0.28f,
) {
    /** Container opacity for [level]: translucent only at full budget, solid otherwise. */
    fun containerAlpha(
        level: EffectLevel,
        sdkInt: Int,
    ): Float =
        when {
            level != EffectLevel.FULL -> 1f
            EffectBudget.allowsBlur(level, sdkInt) -> glassAlpha
            else -> scrimAlpha
        }

    /** Elevation for floating chrome at [level], stepping down as the budget shrinks. */
    fun chromeElevation(
        level: EffectLevel,
        full: Dp,
    ): Dp =
        when (level) {
            EffectLevel.FULL -> full
            EffectLevel.REDUCED -> level1
            EffectLevel.OFF -> 0.dp
        }

    /** The container colour of a glass surface over [base]. */
    fun glassColor(
        base: Color,
        level: EffectLevel,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Color = base.copy(alpha = base.alpha * containerAlpha(level, sdkInt))

    /** The hairline colour on a glass edge, from the scheme's text colour. */
    fun edgeColor(onSurface: Color): Color = onSurface.copy(alpha = edgeGlowAlpha)

    companion object {
        val Default = MawjaDepth()
    }
}

/** The depth tokens in use. */
val LocalMawjaDepth = staticCompositionLocalOf { MawjaDepth.Default }

/** The current depth budget, provided by [MawjaTheme]. Defaults to [EffectLevel.OFF] outside it. */
val LocalEffectLevel = staticCompositionLocalOf { EffectLevel.OFF }

/**
 * Blurs a STATIC backdrop layer (artwork drawn under glass), only when the budget allows a blur
 * (API 31+, full budget). Never attach this to a node that redraws every frame (AGENTS.md).
 */
fun Modifier.mawjaDepthBlur(
    level: EffectLevel,
    depth: MawjaDepth = MawjaDepth.Default,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Modifier = if (EffectBudget.allowsBlur(level, sdkInt)) this.blur(depth.blurRadius) else this
