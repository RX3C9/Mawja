/*
 * Copyright (C) 2026 Mawja contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mawja.android.accessibility

/**
 * Mawja accessibility options (PLAN_V2 #161 large text and high contrast, #162 colour-blind mode,
 * #163 reduce motion), the pure side. Nothing here touches Android, so every rule is a JVM test;
 * [MawjaAccessibility] stores the settings and feeds in what the system says.
 */

/** How strongly the app enlarges text on top of the system font size. The system size always applies. */
enum class TextSizeMode(
    val key: String,
    val scale: Float,
) {
    SYSTEM("system", 1f),
    LARGE("large", 1.15f),
    LARGEST("largest", 1.3f),
    ;

    companion object {
        fun fromKey(key: String?): TextSizeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * Which colour pairs the app avoids. [RED_GREEN] covers deuteranopia and protanopia (the common
 * forms), [BLUE_YELLOW] covers tritanopia. [DEFAULT] keeps every theme colour as designed.
 */
enum class ColorVisionMode(
    val key: String,
) {
    DEFAULT("default"),
    RED_GREEN("red_green"),
    BLUE_YELLOW("blue_yellow"),
    ;

    companion object {
        fun fromKey(key: String?): ColorVisionMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

data class AccessibilitySettings(
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val textSize: TextSizeMode = TextSizeMode.SYSTEM,
    val colorVision: ColorVisionMode = ColorVisionMode.DEFAULT,
)

/** State colours as ARGB ints, for the colour-vision modes: success and error never rely on red vs green. */
data class StatePalette(
    val success: Int,
    val error: Int,
    val onError: Int,
)

object AccessibilityPolicy {
    /** Beyond this the app's fixed-height rows clip text, so the in-app boost stops here (the system size alone may go higher). */
    const val MAX_BOOSTED_FONT_SCALE = 2.0f

    /**
     * The font scale the app renders with: the system's, times the in-app boost. The boost never
     * pushes past [MAX_BOOSTED_FONT_SCALE], and never shrinks text below what the system asks for.
     */
    fun effectiveFontScale(
        systemFontScale: Float,
        mode: TextSizeMode,
    ): Float {
        val system = if (systemFontScale.isFinite() && systemFontScale > 0f) systemFontScale else 1f
        if (mode == TextSizeMode.SYSTEM) return system
        return (system * mode.scale).coerceAtMost(maxOf(MAX_BOOSTED_FONT_SCALE, system))
    }

    /** Developer options "Animation duration scale" set to off, which is also what Android's own "Remove animations" sets. */
    fun systemAnimationsOff(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

    /**
     * Whether non-essential motion (marquees, looping waveforms, skeleton pulses, animated artwork,
     * expressive springs) is off: the in-app switch, the system's animations-off setting, or the
     * battery saver's reduce-motion extra while the saver is active.
     */
    fun reduceMotion(
        settings: AccessibilitySettings,
        animatorDurationScale: Float,
        batterySaverReducesMotion: Boolean,
    ): Boolean = settings.reduceMotion || systemAnimationsOff(animatorDurationScale) || batterySaverReducesMotion

    /** WCAG relative luminance of an sRGB colour, alpha ignored. */
    fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG contrast ratio between two opaque colours, 1.0 to 21.0. */
    fun contrastRatio(
        a: Int,
        b: Int,
    ): Double {
        val la = relativeLuminance(a) + 0.05
        val lb = relativeLuminance(b) + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    /** Black or white, whichever reads better on [background]. */
    fun bestTextOn(background: Int): Int = if (contrastRatio(BLACK, background) >= contrastRatio(WHITE, background)) BLACK else WHITE

    /**
     * [foreground] mixed toward black or white (whichever [background] contrasts with more) just far
     * enough to reach [minRatio], keeping as much of its hue as possible. If even pure black or white
     * cannot reach it, that extreme is returned, which is the most contrast available.
     */
    fun ensureContrast(
        foreground: Int,
        background: Int,
        minRatio: Double,
    ): Int {
        val fg = foreground or OPAQUE
        if (contrastRatio(fg, background) >= minRatio) return fg
        val target = bestTextOn(background)
        var step = 1
        while (step <= MIX_STEPS) {
            val candidate = mix(fg, target, step.toDouble() / MIX_STEPS)
            if (contrastRatio(candidate, background) >= minRatio) return candidate
            step++
        }
        return target
    }

    /** Linear sRGB-space mix of two opaque colours, [amount] 0 = [from], 1 = [to]. */
    fun mix(
        from: Int,
        to: Int,
        amount: Double,
    ): Int {
        val t = amount.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return OPAQUE or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Success and error colours for [mode] on a dark or light theme, or null for [ColorVisionMode.DEFAULT]
     * (the theme's own roles). Built from the Okabe-Ito set: blue against vermillion for red-green,
     * teal against magenta-red for blue-yellow.
     */
    fun statePalette(
        mode: ColorVisionMode,
        dark: Boolean,
    ): StatePalette? =
        when (mode) {
            ColorVisionMode.DEFAULT -> null
            ColorVisionMode.RED_GREEN ->
                if (dark) {
                    StatePalette(success = 0xFF56B4E9.toInt(), error = 0xFFFF9A52.toInt(), onError = BLACK)
                } else {
                    StatePalette(success = 0xFF0072B2.toInt(), error = 0xFFB54700.toInt(), onError = WHITE)
                }
            ColorVisionMode.BLUE_YELLOW ->
                if (dark) {
                    StatePalette(success = 0xFF4DD0C4.toInt(), error = 0xFFFF7AA2.toInt(), onError = BLACK)
                } else {
                    StatePalette(success = 0xFF00796B.toInt(), error = 0xFFC2185B.toInt(), onError = WHITE)
                }
        }

    /**
     * SponsorBlock segment colour for [category] in a colour-vision mode, or null for
     * [ColorVisionMode.DEFAULT] (SponsorBlock's own palette). Red-green uses the Okabe-Ito colours;
     * blue-yellow separates categories by lightness as well as hue. A colour the user picked for a
     * category still wins over both.
     */
    fun sponsorBlockColor(
        category: String,
        mode: ColorVisionMode,
    ): Int? {
        val palette =
            when (mode) {
                ColorVisionMode.DEFAULT -> return null
                ColorVisionMode.RED_GREEN -> RED_GREEN_SEGMENTS
                ColorVisionMode.BLUE_YELLOW -> BLUE_YELLOW_SEGMENTS
            }
        return palette[segmentKey(category)] ?: palette.getValue("sponsor")
    }

    /** Intro and outro share a colour, as they do in SponsorBlock's own palette. */
    private fun segmentKey(category: String): String = if (category == "outro") "intro" else category

    /** Whole percent watched for a TalkBack state description, 0 to 100. */
    fun percentOf(progress: Float): Int = if (progress.isNaN()) 0 else (progress.coerceIn(0f, 1f) * 100f + 0.5f).toInt()

    /** Hours, minutes, seconds of a duration, for a spoken description ("12 minutes 4 seconds" rather than "12:04"). */
    fun durationParts(totalSeconds: Int): Triple<Int, Int, Int> {
        val s = totalSeconds.coerceAtLeast(0)
        return Triple(s / 3600, (s % 3600) / 60, s % 60)
    }

    internal val RED_GREEN_SEGMENTS: Map<String, Int> =
        mapOf(
            "sponsor" to 0xFFE69F00.toInt(),
            "selfpromo" to 0xFFF0E442.toInt(),
            "interaction" to 0xFFCC79A7.toInt(),
            "intro" to 0xFF56B4E9.toInt(),
            "music_offtopic" to 0xFFD55E00.toInt(),
            "filler" to 0xFF009E73.toInt(),
            "preview" to 0xFF0072B2.toInt(),
        )

    internal val BLUE_YELLOW_SEGMENTS: Map<String, Int> =
        mapOf(
            "sponsor" to 0xFFE53935.toInt(),
            "selfpromo" to 0xFFFFFFFF.toInt(),
            "interaction" to 0xFFF48FB1.toInt(),
            "intro" to 0xFF00ACC1.toInt(),
            "music_offtopic" to 0xFF757575.toInt(),
            "filler" to 0xFF8D4E2A.toInt(),
            "preview" to 0xFFB39DDB.toInt(),
        )

    private const val OPAQUE = -0x1000000
    private const val BLACK = -0x1000000
    private const val WHITE = -1
    private const val MIX_STEPS = 20
}
